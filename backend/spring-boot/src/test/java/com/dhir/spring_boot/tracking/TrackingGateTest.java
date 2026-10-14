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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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

    /**
     * Codex review of e47c600: a caller must not read an expired deadline, stall, and then claim the probe
     * slot after another probe has failed and installed a NEW deadline. Here caller A is frozen INSIDE
     * admit() (the clock blocks while A reads it). Another thread's failed() must not get in while A is
     * frozen; it only runs afterwards, and the caller after that is skipped by the new deadline.
     */
    @Test
    void deadlineCheckAndProbeClaimAreOneAtomicStep() throws Exception {
        AtomicBoolean freeze = new AtomicBoolean(false);
        CountDownLatch insideAdmit = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong t = new AtomicLong(1_000 * MS);
        TrackingGate g = new TrackingGate(10_000, () -> {
            if (freeze.get()) {
                insideAdmit.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return t.get();
        });
        g.failed(g.admit());                      // pause until 11_000 ms
        t.addAndGet(10_000 * MS);                 // the pause has just expired
        Admission oldProbe = g.admit();           // probe P1 is running
        assertEquals(Admission.PROBE, oldProbe);

        freeze.set(true);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Admission> a = pool.submit(g::admit);       // A: frozen while reading the clock
            assertTrue(insideAdmit.await(5, TimeUnit.SECONDS));
            freeze.set(false);
            Future<?> b = pool.submit(() -> g.failed(oldProbe)); // P1 fails -> new pause, slot released
            Thread.sleep(200);
            assertFalse(b.isDone(), "failed() must wait until A has left admit()");
            release.countDown();
            assertEquals(Admission.SKIP, a.get(5, TimeUnit.SECONDS), "A saw P1 still running");
            b.get(5, TimeUnit.SECONDS);
            assertEquals(Admission.SKIP, g.admit(), "the NEW pause is respected");
            t.addAndGet(10_000 * MS);
            assertEquals(Admission.PROBE, g.admit(), "after the new pause, one probe again");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    /** Many threads admitting, failing and succeeding at random never have two probes running at once. */
    @Test
    void neverTwoProbesAtOnceUnderConcurrentFailuresAndSuccesses() throws Exception {
        AtomicLong t = new AtomicLong(1_000 * MS);
        TrackingGate g = new TrackingGate(1, t::get); // 1 ms pause
        g.failed(g.admit());
        java.util.concurrent.atomic.AtomicInteger running = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger maxRunning = new java.util.concurrent.atomic.AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (int w = 0; w < 16; w++) {
                final int seed = w;
                fs.add(pool.submit(() -> {
                    java.util.Random r = new java.util.Random(seed);
                    for (int i = 0; i < 20_000; i++) {
                        t.addAndGet(r.nextInt(3) * MS);
                        Admission a = g.admit();
                        if (a == Admission.PROBE) {
                            maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
                            for (int spin = 0; spin < 50; spin++) {
                                Thread.onSpinWait(); // hold the probe slot briefly
                            }
                            running.decrementAndGet(); // before releasing the slot below
                            if (r.nextBoolean()) {
                                g.succeeded(a);
                                g.failed(g.admit()); // close it again so probing continues
                            } else {
                                g.failed(a);
                            }
                        }
                    }
                }));
            }
            for (Future<?> f : fs) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, maxRunning.get(), "at most one probe ever ran at a time");
    }
}
