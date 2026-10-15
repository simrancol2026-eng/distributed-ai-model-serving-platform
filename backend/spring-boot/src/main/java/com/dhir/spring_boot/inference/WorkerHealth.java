package com.dhir.spring_boot.inference;

import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicBoolean;

/** New-policy observations only; never reuses the legacy identity cache. */
final class WorkerHealth {
    record Probe(boolean healthy, String capability, WorkerClient.ServedModel identity) {}
    record Snapshot(boolean live, boolean latestProbeSucceeded, String capability,
                    WorkerClient.ServedModel identity, Long ageMs, int failures) {}
    private final Supplier<Probe> probe;
    private final LongSupplier ticker;
    private final long maxAgeNanos;
    private final AtomicBoolean polling = new AtomicBoolean();
    private Probe lastSuccess;
    private long successAt;
    private int failures;
    private boolean latestSucceeded;
    private long generation;

    WorkerHealth(Supplier<Probe> probe, LongSupplier ticker, long maxAgeNanos) {
        if (maxAgeNanos <= 0) throw new IllegalArgumentException("Health age must be positive");
        this.probe = java.util.Objects.requireNonNull(probe);
        this.ticker = java.util.Objects.requireNonNull(ticker);
        this.maxAgeNanos = maxAgeNanos;
    }

    /** Single-flight, with network I/O outside the observation monitor. */
    void pollOnce() {
        if (!polling.compareAndSet(false, true)) return;
        try {
            long startedGeneration;
            synchronized (this) { startedGeneration = generation; }
            Probe result;
            try { result = probe.get(); }
            catch (RuntimeException e) { result = null; }
            long now = ticker.getAsLong();
            synchronized (this) {
                // A contradictory inference response invalidates a probe already in progress.
                if (generation != startedGeneration) return;
                if (result != null && result.healthy() && result.capability() != null
                        && !result.capability().isBlank()) {
                    lastSuccess = result;
                    successAt = now;
                    failures = 0;
                    latestSucceeded = true;
                } else {
                    failures = Math.min(2, failures + 1);
                    latestSucceeded = false;
                }
            }
        } finally { polling.set(false); }
    }

    synchronized Snapshot snapshot() {
        long age = lastSuccess == null ? 0 : Math.max(0, ticker.getAsLong() - successAt);
        boolean fresh = lastSuccess != null && age <= maxAgeNanos;
        return new Snapshot(fresh && failures < 2, fresh && latestSucceeded,
                lastSuccess == null ? null : lastSuccess.capability(),
                lastSuccess == null ? null : lastSuccess.identity(),
                lastSuccess == null ? null : age / 1_000_000L, failures);
    }

    synchronized void invalidateIdentity() {
        generation++;
        lastSuccess = null;
        latestSucceeded = false;
    }
}
