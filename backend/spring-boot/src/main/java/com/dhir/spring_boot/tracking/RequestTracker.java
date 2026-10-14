package com.dhir.spring_boot.tracking;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import javax.sql.DataSource;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * Every tracking statement is BOUNDED: tracking uses its own JdbcTemplate with a query timeout
 * (platform.tracking.statement-timeout-seconds, default 2 s), so a slow statement or a lock held on
 * inference_requests is cancelled and handled as a failed write. Waiting for a pool connection is
 * bounded separately by spring.datasource.hikari.connection-timeout (2 s).
 *
 * After a failed write, tracking PAUSES (platform.tracking.pause-after-failure-ms, default 10 s; see
 * TrackingGate): new writes are skipped; after the pause exactly one write at a time probes the database.
 *
 * Per request (Trace): once one of a request's writes has failed or been skipped, its remaining writes
 * are skipped too. So tracking can delay a single request at most ONCE, by at most one bounded write
 * (connection wait + statement timeout). Worker time is not tracking overhead and is not counted.
 *
 * Each step only moves a row forward (the WHERE clause checks the current status), so a late or
 * repeated write can never move a finished request back to an earlier state. Because writes are
 * best-effort, a row left in RECEIVED or DISPATCHED means tracking is INCOMPLETE for that request
 * (a write was skipped or failed, or the backend stopped); it is not by itself proof of a crash.
 *
 * Reads (find, recent) are NOT best-effort: they throw DataAccessException, which the API maps to 503.
 */
@Component
public class RequestTracker {

    public static final int MAX_LIST = 100;
    private static final Logger log = LoggerFactory.getLogger(RequestTracker.class);

    private final JdbcClient jdbc;
    private final TrackingGate gate;

    public RequestTracker(DataSource dataSource,
            @Value("${platform.tracking.statement-timeout-seconds:2}") int statementTimeoutSeconds,
            @Value("${platform.tracking.pause-after-failure-ms:10000}") long pauseAfterFailureMs) {
        JdbcTemplate template = new JdbcTemplate(dataSource);
        // JDBC Statement.setQueryTimeout: the driver cancels the statement when it runs longer
        // (on PostgreSQL this also ends a wait for a table lock).
        template.setQueryTimeout(Math.max(1, statementTimeoutSeconds));
        this.jdbc = JdbcClient.create(template);
        this.gate = new TrackingGate(pauseAfterFailureMs);
    }

    /**
     * Tracking state of ONE request, used only by the thread handling that request.
     * suppressed = an earlier write of this request failed or was skipped; later writes are skipped.
     */
    public static final class Trace {
        private final String requestId;
        private boolean suppressed;

        Trace(String requestId) {
            this.requestId = requestId;
        }

        public String requestId() {
            return requestId;
        }

        public boolean suppressed() {
            return suppressed;
        }
    }

    /** Step 1: the request arrived. Values are what the client sent (trimmed, length-capped). */
    public Trace received(String requestId, String capability, String model, String version, Integer inputChars) {
        Trace trace = new Trace(requestId);
        write("received", trace, () -> jdbc.sql("""
                INSERT INTO inference_requests
                    (request_id, status, capability, requested_model, requested_version, input_chars)
                VALUES (:id, 'RECEIVED', :capability, :model, :version, :inputChars)""")
                .param("id", requestId)
                .param("capability", cut(capability, 16))
                .param("model", cut(model, 64))
                .param("version", cut(version, 64))
                .param("inputChars", inputChars)
                .update());
        return trace;
    }

    /**
     * Step 2: a worker was chosen and the request is being sent to it.
     * resolvedVersion / artifact / revision are null for requests that named no model.
     */
    public void dispatched(Trace trace, String workerEndpoint, String resolvedVersion, String artifact,
            String artifactRevision) {
        String requestId = trace.requestId;
        write("dispatched", trace, () -> jdbc.sql("""
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
    public void completed(Trace trace, int httpStatus, String errorCode, String workerId, long totalMs) {
        String requestId = trace.requestId;
        write("completed", trace, () -> jdbc.sql("""
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

    private void write(String step, Trace trace, Runnable sql) {
        String requestId = trace.requestId;
        if (trace.suppressed) {
            log.debug("request tracking skipped (earlier write of this request failed or was skipped) "
                    + "requestId={} step={}", requestId, step);
            return;
        }
        TrackingGate.Admission ticket = gate.admit();
        if (ticket == TrackingGate.Admission.SKIP) {
            trace.suppressed = true;
            log.debug("request tracking skipped (paused) requestId={} step={}", requestId, step);
            return;
        }
        try {
            sql.run();
            if (gate.succeeded(ticket)) {
                log.info("request tracking resumed");
            }
        } catch (DataAccessException e) {
            trace.suppressed = true;
            gate.failed(ticket);
            log.warn("request tracking failed requestId={} step={} reason={}; new writes paused",
                    requestId, step, e.getMessage());
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
