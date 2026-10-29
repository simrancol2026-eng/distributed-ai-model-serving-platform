package com.dhir.spring_boot.tracking;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;

/** H2-only persistence check, not real PostgreSQL evidence. */
class FinalEndpointTrackingTest {
    @Test void completionUsesFinalEndpointAndCannotBeOverwritten() {
        var dataSource = new SingleConnectionDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH", "sa", "", true);
        try {
        var schema = new ResourceDatabasePopulator(new ClassPathResource("db/migration/V2__inference_requests.sql"));
        schema.execute(dataSource);
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("ALTER TABLE inference_requests ADD COLUMN routing_decision VARCHAR(8000)");
        var tracker = new RequestTracker(dataSource, 2, 0);
        String id = UUID.randomUUID().toString();
        var trace = tracker.received(id, "TEXT", null, null, 5);
        tracker.dispatched(trace, "a:8011", null, null, null, "{}");
        tracker.dispatched(trace, "b:8012", null, null, null, "{}");
        tracker.completed(trace, 200, null, "worker-b", 20, "{}", "b:8012");
        tracker.completed(trace, 502, "WORKER_ERROR", null, 25, "{}", "a:8011");
        var row = jdbc.queryForMap("SELECT worker_endpoint, worker_id, status FROM inference_requests WHERE request_id=?", id);
        assertEquals("b:8012", row.get("worker_endpoint"));
        assertEquals("worker-b", row.get("worker_id"));
        assertEquals("SUCCEEDED", row.get("status"));

        String legacyId = UUID.randomUUID().toString();
        var legacy = tracker.received(legacyId, "TEXT", null, null, 5);
        tracker.dispatched(legacy, "a:8011", null, null, null, "{}");
        tracker.completed(legacy, 200, null, "worker-a", 10, "{}");
        assertEquals("a:8011", jdbc.queryForObject("SELECT worker_endpoint FROM inference_requests WHERE request_id=?",
                String.class, legacyId));
        String coldId = UUID.randomUUID().toString();
        var cold = tracker.received(coldId, "TEXT", null, null, 5);
        tracker.completed(cold,503,"NO_FRESH_OBSERVATION",null,1,"{}",null);
        var coldRow = jdbc.queryForMap("SELECT status, dispatched_at, worker_endpoint, error_code FROM inference_requests WHERE request_id=?",coldId);
        assertEquals("FAILED",coldRow.get("status"));
        assertNull(coldRow.get("dispatched_at")); assertNull(coldRow.get("worker_endpoint"));
        assertEquals("NO_FRESH_OBSERVATION",coldRow.get("error_code"));
        } finally { dataSource.destroy(); }
    }
}
