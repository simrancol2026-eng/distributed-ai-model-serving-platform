package com.dhir.spring_boot.tracking;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Decides whether a tracking write may touch the database (Sprint 2, D-042).
 *
 *   OPEN    - every write goes to the database.
 *   PAUSED  - after a failed write: every write is skipped until the pause ends.
 *   PROBING - after the pause: exactly ONE write at a time is admitted (compare-and-set);
 *             all others are skipped while it runs. Success -> OPEN, failure -> PAUSED again.
 *
 * Writes that were already running when the first failure happened cannot be recalled: each of them
 * still finishes or times out once (bounded by the statement / connection timeouts). The gate only
 * guarantees that no NEW write starts during a pause and that at most one probe runs at a time.
 *
 * Plain Java with an injectable clock so it can be unit-tested without Spring or a database.
 */
public final class TrackingGate {

    /** A ticket for one write: whether it may run, and whether it is the probe. */
    public enum Admission { RUN, PROBE, SKIP }

    private final long pauseNanos;
    private final LongSupplier clock;
    /** 0 = open; otherwise the clock value at which the current pause ends. */
    private final AtomicLong pausedUntil = new AtomicLong(0);
    private final AtomicBoolean probing = new AtomicBoolean(false);

    public TrackingGate(long pauseMs) {
        this(pauseMs, System::nanoTime);
    }

    TrackingGate(long pauseMs, LongSupplier clock) {
        this.pauseNanos = Math.max(0, pauseMs) * 1_000_000L;
        this.clock = clock;
    }

    public Admission admit() {
        long until = pausedUntil.get();
        if (until == 0) {
            return Admission.RUN;
        }
        if (clock.getAsLong() - until < 0) {
            return Admission.SKIP; // still paused
        }
        return probing.compareAndSet(false, true) ? Admission.PROBE : Admission.SKIP;
    }

    /** The admitted write succeeded. Returns true if this ended a pause (tracking resumed). */
    public boolean succeeded(Admission a) {
        if (a != Admission.PROBE) {
            return false;
        }
        pausedUntil.set(0);
        probing.set(false);
        return true;
    }

    /** The admitted write failed: start (or restart) the pause. No pause when pauseMs is 0. */
    public void failed(Admission a) {
        if (pauseNanos > 0) {
            long until = clock.getAsLong() + pauseNanos;
            pausedUntil.set(until == 0 ? 1 : until);
        }
        if (a == Admission.PROBE) {
            probing.set(false);
        }
    }

    public boolean isOpen() {
        return pausedUntil.get() == 0;
    }
}
