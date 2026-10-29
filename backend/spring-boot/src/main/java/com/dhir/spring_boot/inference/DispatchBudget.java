package com.dhir.spring_boot.inference;

import java.time.Duration;
import java.util.function.LongSupplier;

/** One monotonic dispatch allowance; tracking elapsed time consumes it but JDBC is not cancelled. */
final class DispatchBudget {
    private final LongSupplier clock;
    private final long started;
    private final long allowance;

    DispatchBudget(long milliseconds) { this(milliseconds, System::nanoTime); }

    DispatchBudget(long milliseconds, LongSupplier clock) {
        if (milliseconds <= 0 || milliseconds > Long.MAX_VALUE / 1_000_000L) {
            throw new IllegalArgumentException("Dispatch budget must be positive and representable in nanoseconds");
        }
        this.clock = java.util.Objects.requireNonNull(clock);
        this.allowance = milliseconds * 1_000_000L;
        this.started = clock.getAsLong();
    }

    Duration remaining() {
        // Subtraction handles nanoTime wraparound for representable elapsed intervals.
        long elapsed = clock.getAsLong() - started;
        return Duration.ofNanos(elapsed >= allowance ? 0 : allowance - Math.max(0, elapsed));
    }

    Duration allowance() { return Duration.ofNanos(allowance); }

    Duration cappedTimeout(long configuredMilliseconds) {
        if (configuredMilliseconds <= 0) throw new IllegalArgumentException("Timeout must be positive");
        Duration left = remaining();
        Duration configured = Duration.ofMillis(configuredMilliseconds);
        return left.compareTo(configured) < 0 ? left : configured;
    }
}
