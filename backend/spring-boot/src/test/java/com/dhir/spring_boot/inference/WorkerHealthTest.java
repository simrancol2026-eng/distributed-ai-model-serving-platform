package com.dhir.spring_boot.inference;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class WorkerHealthTest {
    private static WorkerHealth.Probe good() {
        return new WorkerHealth.Probe(true, "TEXT", new WorkerClient.ServedModel("model", "rev"));
    }

    @Test void unknownAndExpiredAreNotLive() {
        var now = new AtomicLong();
        var h = new WorkerHealth(WorkerHealthTest::good, now::get, 5_000_000);
        assertFalse(h.snapshot().live());
        assertNull(h.snapshot().ageMs());
        h.pollOnce();
        var captured = h.snapshot();
        now.set(5_000_001);
        assertFalse(h.snapshot().live());
        assertFalse(h.snapshot().latestProbeSucceeded());
        assertTrue(captured.live());
        assertEquals("rev", captured.identity().revision());
    }

    @Test void oneSlowProbeDoesNotExpireTheLastSuccess() {
        // Real timing: success ends at 0, the next probe starts 2 s later and times out after 2 s (t=4 s),
        // and the one after that only starts at t=6 s. The worker must still be live in between.
        var now = new AtomicLong();
        var fail = new AtomicBoolean();
        var h = new WorkerHealth(() -> { if (fail.get()) throw new IllegalStateException(); return good(); },
                now::get, WorkerPool.healthFreshnessNanos(2000, 2000));
        h.pollOnce();
        fail.set(true);
        now.set(4_000_000_000L);
        h.pollOnce();
        now.set(5_100_000_000L);
        assertTrue(h.snapshot().live());
        now.set(8_000_000_000L);
        h.pollOnce();
        assertFalse(h.snapshot().live());
    }

    @Test void errorFromProbeCountsAsFailedProbe() {
        var h = new WorkerHealth(() -> { throw new AssertionError("probe blew up"); }, () -> 0, 100);
        assertDoesNotThrow(h::pollOnce);
        assertFalse(h.snapshot().latestProbeSucceeded());
        assertEquals(1, h.snapshot().failures());
    }

    @Test void twoFailuresExcludeAndOneSuccessReadmits() {
        var fail = new AtomicBoolean();
        var h = new WorkerHealth(() -> { if (fail.get()) throw new IllegalStateException(); return good(); },
                () -> 0, 100);
        h.pollOnce();
        fail.set(true);
        h.pollOnce();
        assertTrue(h.snapshot().live());
        assertFalse(h.snapshot().latestProbeSucceeded());
        h.pollOnce();
        assertFalse(h.snapshot().live());
        fail.set(false);
        h.pollOnce();
        assertTrue(h.snapshot().live());
        assertEquals(0, h.snapshot().failures());
    }

    @Test void failedFirstProbeAndMalformedCapabilityCannotEstablishLiveness() {
        var h = new WorkerHealth(() -> new WorkerHealth.Probe(true, " ", null), () -> 0, 100);
        h.pollOnce();
        assertFalse(h.snapshot().live());
        assertNull(h.snapshot().identity());
    }

    @Test void successfulProbeReplacesIdentityRatherThanKeepingStaleRevision() {
        var result = new AtomicReference<>(good());
        var h = new WorkerHealth(result::get, () -> 0, 100);
        h.pollOnce();
        result.set(new WorkerHealth.Probe(true, "TEXT", null));
        h.pollOnce();
        assertTrue(h.snapshot().latestProbeSucceeded());
        assertNull(h.snapshot().identity());
    }

    @Test void concurrentPollIsSingleFlightAndInvalidationWinsOverOldProbe() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var h = new WorkerHealth(() -> {
            calls.incrementAndGet();
            entered.countDown();
            try { if (!release.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            return good();
        }, () -> 0, 100);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var running = executor.submit(h::pollOnce);
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                h.pollOnce();
                assertEquals(1, calls.get());
                assertFalse(h.snapshot().live()); // read does not wait for probe network I/O
                h.invalidateIdentity();
            } finally { release.countDown(); }
            running.get(3, TimeUnit.SECONDS);
            assertFalse(h.snapshot().live());
            h.pollOnce();
            assertTrue(h.snapshot().live());
        }
    }
}
