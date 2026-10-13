package com.dhir.spring_boot.tracking;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Persists the lifecycle of every POST /api/inference request in the inference_requests table
 * (db/migration/V2__inference_requests.sql): RECEIVED -> DISPATCHED -> SUCCEEDED | FAILED.
 *
 * Writes are BEST-EFFORT: if the database cannot be written, the failure is logged
 * ("request tracking failed ...") and the inference request is served exactly as it would be
 * without tracking. Tracking is an audit trail; it must not make inference less available
 * (requests that name no model do not need the database at all).
 *
 * After a failed write, tracking PAUSES for platform.tracking.pause-after-failure-ms (default 10 s):
 * writes in that window are skipped instead of each waiting for a database that is down
 * (up to the connection timeout every time). The next write after the pause tries again.
 *
 * Each step only moves a row forward (the WHERE clause checks the current status), so a late or
 * repeated write can never move a finished request back to an earlier state.
 *
 * Reads (find, recent) are NOT best-effort: they throw DataAccessException, which the API maps to 503.
 */
@Component
public class RequestTracker {

    public static final int MAX_LIST = 100;
    private static final Logger log = LoggerFactory.getLogger(RequestTracker.class);

    private final JdbcClient jdbc;
    private final long pauseNanos;
    /** While paused, writes are skipped until System.nanoTime() reaches pausedUntil. */
    private volatile long pausedUntil;
    private volatile boolean paused;

    public RequestTracker(JdbcClient jdbc,
            @Value("${platform.tracking.pause-after-failure-ms:10000}") long pauseAfterFailureMs) {
        this.jdbc = jdbc;
        this.pauseNanos = pauseAfterFailureMs * 1_000_000L;
    }

    /** Step 1: the request arrived. Values are what the client sent (trimmed, length-capped). */
    public void received(String requestId, String capability, String model, String version, Integer inputChars) {
        write("received", requestId, () -> jdbc.sql("""
                INSERT INTO inference_requests
                    (request_id, status, capability, requested_model, requested_version, input_chars)
                VALUES (:id, 'RECEIVED', :capability, :model, :version, :inputChars)""")
                .param("id", requestId)
                .param("capability", cut(capability, 16))
                .param("model", cut(model, 64))
                .param("version", cut(version, 64))
                .param("inputChars", inputChars)
                .update());
    }

    /**
     * Step 2: a worker was chosen and the request is being sent to it.
     * resolvedVersion / artifact / revision are null for requests that named no model.
     */
    public void dispatched(String requestId, String workerEndpoint, String resolvedVersion, String artifact,
            String artifactRevision) {
        write("dispatched", requestId, () -> jdbc.sql("""
                UPDATE inference_requests
                SET status = 'DISPATCHED', dispatched_at = CURRENT_TIMESTAMP, worker_endpoint = :endpoint,
                    resolved_version = :resolved, artifact = :artifact, artifact_revision = :revision
                WHERE request_id = :id AND status = 'RECEIVED'""")
                .param("id", requestId)
                .param("endpoint", cut(workerEndpoint, 255))
                .param("resolved", cut(resolvedVersion, 64))
                .param("artifact", cut(artifact, 200))
                .param("revision", cut(artifactRevision, 64))
                .update());
    }

    /** Step 3: the response was decided. errorCode == null means success (SUCCEEDED), else FAILED. */
    public void completed(String requestId, int httpStatus, String errorCode, String workerId, long totalMs) {
        write("completed", requestId, () -> jdbc.sql("""
                UPDATE inference_requests
                SET status = :status, completed_at = CURRENT_TIMESTAMP, http_status = :http,
                    error_code = :error, worker_id = :workerId, total_ms = :totalMs
                WHERE request_id = :id AND status IN ('RECEIVED', 'DISPATCHED')""")
                .param("id", requestId)
                .param("status", errorCode == null ? "SUCCEEDED" : "FAILED")
                .param("http", httpStatus)
                .param("error", cut(errorCode, 40))
                .param("workerId", cut(workerId, 100))
                .param("totalMs", totalMs)
                .update());
    }

    public Optional<RequestRecord> find(String requestId) {
        return jdbc.sql("SELECT * FROM inference_requests WHERE request_id = :id")
                .param("id", requestId)
                .query((rs, n) -> record(rs))
                .optional();
    }

    /** Most recent requests first (arrival order), at most `limit` (1..MAX_LIST). */
    public List<RequestRecord> recent(int limit) {
        return jdbc.sql("SELECT * FROM inference_requests ORDER BY id DESC LIMIT :limit")
                .param("limit", limit)
                .query((rs, n) -> record(rs))
                .list();
    }

    private void write(String step, String requestId, Runnable sql) {
        if (paused && System.nanoTime() - pausedUntil < 0) {
            log.debug("request tracking skipped (paused) requestId={} step={}", requestId, step);
            return;
        }
        try {
            sql.run();
            if (paused) {
                paused = false;
                log.info("request tracking resumed");
            }
        } catch (DataAccessException e) {
            pausedUntil = System.nanoTime() + pauseNanos;
            paused = pauseNanos > 0;
            log.warn("request tracking failed requestId={} step={} reason={}; writes paused for {} ms",
                    requestId, step, e.getMessage(), pauseNanos / 1_000_000);
        }
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static RequestRecord record(ResultSet rs) throws SQLException {
        return new RequestRecord(rs.getString("request_id"), rs.getString("status"), rs.getString("capability"),
                rs.getString("requested_model"), rs.getString("requested_version"),
                rs.getObject("input_chars", Integer.class), rs.getString("resolved_version"),
                rs.getString("artifact"), rs.getString("artifact_revision"), rs.getString("worker_endpoint"),
                rs.getString("worker_id"), rs.getObject("http_status", Integer.class), rs.getString("error_code"),
                rs.getObject("total_ms", Long.class), instant(rs, "received_at"), instant(rs, "dispatched_at"),
                instant(rs, "completed_at"));
    }

    private static String instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant().toString();
    }
}
