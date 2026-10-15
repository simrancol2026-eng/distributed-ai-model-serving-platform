package com.dhir.spring_boot.inference;

/** Successful predict-call durations only. Caller supplies monotonic timestamps. */
final class LatencyEstimate {
    private final double alpha;
    private final long staleAfterNanos;
    private long samples;
    private long lastSampleNanos;
    private double ewmaMs;

    LatencyEstimate(double alpha) {
        this(alpha, 30_000_000_000L);
    }

    LatencyEstimate(double alpha, long staleAfterNanos) {
        if (!Double.isFinite(alpha) || alpha <= 0 || alpha > 1) {
            throw new IllegalArgumentException("Latency alpha must be finite and in (0, 1]");
        }
        this.alpha = alpha;
        if (staleAfterNanos <= 0) throw new IllegalArgumentException("Staleness must be positive");
        this.staleAfterNanos = staleAfterNanos;
    }

    synchronized void recordSuccess(long durationNanos, long completedAtNanos) {
        if (durationNanos < 0) throw new IllegalArgumentException("Negative duration");
        double durationMs = durationNanos / 1_000_000.0;
        boolean stale = samples > 0 && completedAtNanos - lastSampleNanos > staleAfterNanos;
        ewmaMs = samples == 0 || stale ? durationMs : alpha * durationMs + (1 - alpha) * ewmaMs;
        samples++;
        lastSampleNanos = completedAtNanos;
    }

    synchronized Snapshot snapshot(long nowNanos) {
        return new Snapshot(samples == 0 ? null : ewmaMs, samples,
                samples == 0 ? null : Math.max(0, nowNanos - lastSampleNanos) / 1_000_000L);
    }

    record Snapshot(Double ewmaMs, long samples, Long ageMs) {}
}
