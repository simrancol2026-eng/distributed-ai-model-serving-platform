package com.dhir.spring_boot.inference;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Bounded background probing. Owner must close on shutdown; no request calls pollOnce. */
final class HealthPoller implements AutoCloseable {
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
            executor.scheduleWithFixedDelay(observation::pollOnce, 0, intervalMs, TimeUnit.MILLISECONDS);
        }
    }

    @Override public void close() { executor.shutdownNow(); }
}
