package com.dhir.spring_boot.inference;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class LeastInFlightTest {
    private WorkerPool pool(String policy) {
        return new WorkerPool("http://localhost:8001,http://localhost:8002", "TEXT",
                100, 100, 100, 0, policy);
    }

    @Test void rejectsUnknownPolicyBeforeCreatingClients() {
        assertThrows(IllegalArgumentException.class, () -> pool("typo"));
    }

    @Test void rotatesIdleTiesAndCloseIsIdempotent() {
        WorkerPool p = pool("LEAST_IN_FLIGHT");
        for (int i = 0; i < 6; i++) {
            var r = p.reserve();
            assertSame(p.all().get(i % 2), r.worker());
            assertEquals(0, r.before().get(r.worker().endpoint()));
            r.close(); r.close();
            assertTrue(p.counts().values().stream().allMatch(n -> n == 0));
        }
    }

    @Test void busyWorkerIsAvoidedButEligibilityCannotBeWidened() {
        WorkerPool p = pool("LEAST_IN_FLIGHT");
        try (var a = p.reserve()) {
            for (int i = 0; i < 4; i++) {
                try (var b = p.reserve()) {
                    assertSame(p.all().get(1), b.worker());
                    assertEquals(1, b.before().get(a.worker().endpoint()));
                }
            }
            try (var onlyA = p.reserve(List.of(a.worker()), "artifact", "revision")) {
                assertSame(a.worker(), onlyA.worker());
            }
        }
        assertTrue(p.counts().values().stream().allMatch(n -> n == 0));
    }

    @Test void roundRobinStillAlternatesEvenWhenOneWorkerIsBusy() {
        WorkerPool p = pool("ROUND_ROBIN");
        try (var a = p.reserve()) {
            try (var b = p.reserve()) { assertSame(p.all().get(1), b.worker()); }
            try (var next = p.reserve()) { assertSame(a.worker(), next.worker()); }
        }
    }

    @Test void controllerReleasesReservationWhenTrackingThrowsUnexpectedly() {
        WorkerPool p = pool("LEAST_IN_FLIGHT");
        var dataSource = new org.springframework.jdbc.datasource.AbstractDataSource() {
            @Override public java.sql.Connection getConnection() throws java.sql.SQLException {
                throw new java.sql.SQLException("test database unavailable");
            }
            @Override public java.sql.Connection getConnection(String u, String pw) throws java.sql.SQLException {
                return getConnection();
            }
        };
        var tracker = new com.dhir.spring_boot.tracking.RequestTracker(dataSource, 1, 1000) {
            @Override public void dispatched(Trace trace, String endpoint, String version,
                    String artifact, String revision, String decision) {
                assertEquals(1, p.counts().values().stream().mapToInt(Integer::intValue).sum());
                throw new IllegalStateException("unexpected tracking failure");
            }
        };
        var controller = new InferenceController(p, null, tracker);
        assertThrows(IllegalStateException.class,
                () -> controller.infer(new InferenceDtos.InferenceRequest("TEXT", "hello", null, null)));
        assertTrue(p.counts().values().stream().allMatch(n -> n == 0));
    }

    @Test void simultaneousHeldReservationsAreBalancedAndReleased() throws Exception {
        WorkerPool p = pool("LEAST_IN_FLIGHT");
        var start = new CountDownLatch(1);
        var held = new CountDownLatch(32);
        var release = new CountDownLatch(1);
        var futures = new ArrayList<Future<?>>();
        try (var executor = Executors.newFixedThreadPool(32)) {
            try {
                for (int i = 0; i < 32; i++) futures.add(executor.submit(() -> {
                    start.await();
                    try (var r = p.reserve()) {
                        held.countDown();
                        if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("release timeout");
                    }
                    return null;
                }));
                start.countDown();
                assertTrue(held.await(10, TimeUnit.SECONDS));
                assertEquals(List.of(16, 16), p.all().stream().map(w -> p.counts().get(w.endpoint())).toList());
            } finally { release.countDown(); }
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
        }
        assertTrue(p.counts().values().stream().allMatch(n -> n == 0));
    }
}

