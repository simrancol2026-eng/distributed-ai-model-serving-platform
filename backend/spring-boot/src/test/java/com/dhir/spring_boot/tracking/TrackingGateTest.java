package com.dhir.spring_boot.tracking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.dhir.spring_boot.tracking.TrackingGate.Admission;

/** TrackingGate with a hand-driven clock (nanoseconds): no Spring, no database, no sleeping. */
class TrackingGateTest {

    static final long MS = 1_000_000L;
    final AtomicLong now = new AtomicLong(1_000 * MS);
    final TrackingGate gate = new TrackingGate(10_000, now::get); // 10 s pause

    @Test
    void openGateLetsEveryWriteRun() {
        for (int i = 0; i < 5; i++) {
            assertEquals(Admission.RUN, gate.admit());
        }
        assertTrue(gate.isOpen());
    }

    @Test
    void afterAFailureNewWritesAreSkippedUntilThePauseEnds() {
        gate.failed(gate.admit());
        assertFalse(gate.isOpen());
        assertEquals(Admission.SKIP, gate.admit());
        now.addAndGet(9_999 * MS);
        assertEquals(Admission.SKIP, gate.admit(), "still inside the 10 s pause");
        now.addAndGet(1 * MS);
        assertEquals(Admission.PROBE, gate.admit(), "pause over: one probe is admitted");
    }

    @Test
    void onlyOneProbeAtATimeAndItsSuccessReopens() {
        gate.failed(gate.admit());
        now.addAndGet(10_000 * MS);
        Admission probe = gate.admit();
        assertEquals(Admission.PROBE, probe);
        assertEquals(Admission.SKIP, gate.admit(), "a second caller is skipped while the probe runs");
        assertTrue(gate.succeeded(probe), "a successful probe resumes tracking");
        assertTrue(gate.isOpen());
        assertEquals(Admission.RUN, gate.admit());
    }

    @Test
    void aFailedProbeStartsANewPause() {
        gate.failed(gate.admit());
        now.addAndGet(10_000 * MS);
        Admission probe = gate.admit();
        gate.failed(probe);
        assertEquals(Admission.SKIP, gate.admit());
        now.addAndGet(10_000 * MS);
        assertEquals(Admission.PROBE, gate.admit(), "the probe slot was released after the failure");
    }

    @Test
    void zeroPauseNeverSkips() {
        TrackingGate noPause = new TrackingGate(0, now::get);
        noPause.failed(noPause.admit());
        assertTrue(noPause.isOpen());
        assertEquals(Admission.RUN, noPause.admit());
    }

    @Test
    void manyConcurrentCallersAfterThePauseAdmitExactlyOneProbe() throws Exception {
        gate.failed(gate.admit());
        now.addAndGet(10_000 * MS);
        int threads = 64;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Admission>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<Admission> c = () -> {
                    start.await();
                    return gate.admit();
                };
                results.add(pool.submit(c));
            }
            start.countDown();
            int probes = 0;
            int skips = 0;
            for (Future<Admission> f : results) {
                Admission a = f.get();
                if (a == Admission.PROBE) {
                    probes++;
                } else if (a == Admission.SKIP) {
                    skips++;
                }
            }
            assertEquals(1, probes, "exactly one probe");
            assertEquals(threads - 1, skips, "everyone else skipped");
        } finally {
            pool.shutdownNow();
        }
    }
}
