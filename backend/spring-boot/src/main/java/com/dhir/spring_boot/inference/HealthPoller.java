package com.dhir.spring_boot.inference;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bounded background probing. Owner must close on shutdown; no request calls pollOnce. */
final class HealthPoller implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(HealthPoller.class);
    private final ScheduledExecutorService executor;

    HealthPoller(List<WorkerHealth> observations, long intervalMs) {
        if (observations.isEmpty() || intervalMs <= 0) {
            throw new IllegalArgumentException("Workers and positive poll interval required");
        }
        executor = Executors.newScheduledThreadPool(Math.min(4, observations.size()), task -> {
            Thread thread = new Thread(task, "worker-health-probe");
            thread.setDaemon(true);
            return thread;
        });
        for (WorkerHealth observation : observations) {
            executor.scheduleWithFixedDelay(() -> pollSafely(observation), 0, intervalMs, TimeUnit.MILLISECONDS);
        }
    }

    /** A task that throws is never run again by scheduleWithFixedDelay, so nothing may escape. */
    private static void pollSafely(WorkerHealth observation) {
        try {
            observation.pollOnce();
        } catch (Throwable t) {
            log.warn("worker health poll failed; will retry on the next interval", t);
        }
    }

    @Override public void close() { executor.shutdownNow(); }
}
