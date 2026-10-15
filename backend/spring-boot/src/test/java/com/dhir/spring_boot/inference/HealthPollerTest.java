package com.dhir.spring_boot.inference;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class HealthPollerTest {
    @Test void pollingContinuesAfterAProbeThrowsAnError() throws Exception {
        var calls = new CountDownLatch(3);
        var observation = new WorkerHealth(() -> {
            calls.countDown();
            throw new AssertionError("probe blew up");
        }, System::nanoTime, 5_000_000_000L);
        try (var poller = new HealthPoller(List.of(observation), 10)) {
            assertTrue(calls.await(3, TimeUnit.SECONDS), "polling stopped after the first Error");
        }
    }

    @Test void boundedParallelismAndShutdownInterruptsActiveProbes() throws Exception {
        var started = new CountDownLatch(4);
        var interrupted = new CountDownLatch(4);
        var blocked = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var observations = new ArrayList<WorkerHealth>();
        for (int i = 0; i < 8; i++) observations.add(new WorkerHealth(() -> {
            calls.incrementAndGet();
            started.countDown();
            try { blocked.await(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); interrupted.countDown(); }
            return null;
        }, System::nanoTime, 5_000_000_000L));
        var poller = new HealthPoller(observations, 2000);
        try {
            assertTrue(started.await(3, TimeUnit.SECONDS));
            assertEquals(4, calls.get());
            poller.close();
            assertTrue(interrupted.await(3, TimeUnit.SECONDS));
            assertEquals(4, calls.get());
            poller.close(); // repeated shutdown is harmless
        } finally { poller.close(); blocked.countDown(); }
    }
}
