package com.dhir.spring_boot.tracking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;

/**
 * Per-request suppression (Codex review of e47c600): once one write of a request fails, that request's
 * later writes are skipped, so tracking delays one request at most once, even when the worker takes
 * longer than the pause. The DataSource here fails every connection attempt and counts them.
 * No Spring context, no database.
 */
class RequestTrackerSuppressionTest {

    final AtomicInteger connectionAttempts = new AtomicInteger();

    DataSource failingDataSource() {
        return (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { DataSource.class },
                (proxy, method, args) -> {
                    if (method.getName().equals("getConnection")) {
                        connectionAttempts.incrementAndGet();
                        throw new SQLException("database down (test)");
                    }
                    if (method.getName().equals("toString")) {
                        return "failingDataSource";
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void afterTheFirstFailedWriteTheSameRequestNeverTouchesTheDatabaseAgain() {
        // pause 0: the global gate never pauses, so only per-request suppression can save the later writes.
        RequestTracker tracker = new RequestTracker(failingDataSource(), 2, 0);
        RequestTracker.Trace trace = tracker.received("11111111-1111-1111-1111-111111111111", "TEXT", null, null, 5);
        assertTrue(trace.suppressed());
        tracker.dispatched(trace, "127.0.0.1:8001", null, null, null, null);
        tracker.completed(trace, 200, null, "worker-a", 12, null);
        assertEquals(1, connectionAttempts.get(), "only the first write tried the database");
    }

    @Test
    void aSlowWorkerOutlastingThePauseDoesNotCauseASecondDelayForTheSameRequest() throws Exception {
        RequestTracker tracker = new RequestTracker(failingDataSource(), 2, 5); // 5 ms pause
        RequestTracker.Trace trace = tracker.received("22222222-2222-2222-2222-222222222222", null, null, null, 1);
        tracker.dispatched(trace, "127.0.0.1:8001", null, null, null, null);
        Thread.sleep(50); // "worker time", much longer than the pause
        tracker.completed(trace, 200, null, "worker-a", 60, null);
        assertEquals(1, connectionAttempts.get(), "completion after the pause was not a second attempt");

        // A NEW request after the pause may probe (that is the gate's job), and fails once itself.
        RequestTracker.Trace next = tracker.received("33333333-3333-3333-3333-333333333333", null, null, null, 1);
        assertEquals(2, connectionAttempts.get());
        tracker.completed(next, 200, null, "worker-a", 1, null);
        assertEquals(2, connectionAttempts.get());
    }
}
