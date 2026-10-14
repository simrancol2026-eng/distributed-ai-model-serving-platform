package com.dhir.spring_boot.tracking;

import java.util.function.LongSupplier;

/**
 * Decides whether a tracking write may touch the database (Sprint 2, D-042).
 *
 *   OPEN    - every write goes to the database.
 *   PAUSED  - after a failed write: every write is skipped until the pause ends.
 *   PROBING - after the pause: exactly ONE write at a time is admitted; all others are skipped
 *             while it runs. Success -> OPEN, failure -> PAUSED again (new deadline).
 *
 * All state (open flag, deadline, probe slot) changes inside one short synchronized section, so
 * reading the deadline and claiming the probe slot are a single atomic step: a caller can never be
 * admitted as a probe against a deadline that another thread has just replaced. The sections only
 * compare and assign a few fields (no I/O), so the lock is held for nanoseconds.
 *
 * Writes that were already running when the first failure happened cannot be recalled; each of them
 * still finishes or times out once (bounded by the statement / connection timeouts).
 *
 * Plain Java with an injectable clock so it can be unit-tested without Spring or a database.
 */
public final class TrackingGate {

    /** A ticket for one write: whether it may run, and whether it is the probe. */
    public enum Admission { RUN, PROBE, SKIP }

    private final long pauseNanos;
    private final LongSupplier clock;

    private boolean open = true;
    private long pausedUntil;   // meaningful only while !open
    private boolean probing;    // a probe is currently running

    public TrackingGate(long pauseMs) {
        this(pauseMs, System::nanoTime);
    }

    TrackingGate(long pauseMs, LongSupplier clock) {
        this.pauseNanos = Math.max(0, pauseMs) * 1_000_000L;
        this.clock = clock;
    }

    public synchronized Admission admit() {
        if (open) {
            return Admission.RUN;
        }
        if (clock.getAsLong() - pausedUntil < 0 || probing) {
            return Admission.SKIP; // still paused, or another probe is running
        }
        probing = true;
        return Admission.PROBE;
    }

    /** The admitted write succeeded. Returns true if this ended a pause (tracking resumed). */
    public synchronized boolean succeeded(Admission a) {
        if (a != Admission.PROBE) {
            return false;
        }
        probing = false;
        open = true;
        return true;
    }

    /** The admitted write failed: start (or restart) the pause. No pause when pauseMs is 0. */
    public synchronized void failed(Admission a) {
        if (a == Admission.PROBE) {
            probing = false;
        }
        if (pauseNanos > 0) {
            open = false;
            pausedUntil = clock.getAsLong() + pauseNanos;
        }
    }

    public synchronized boolean isOpen() {
        return open;
    }
}
