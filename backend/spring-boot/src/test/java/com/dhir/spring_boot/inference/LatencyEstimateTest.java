package com.dhir.spring_boot.inference;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class LatencyEstimateTest {
    @Test void staleRecoveryReplacesOldEstimateRatherThanBlending() {
        var e = new LatencyEstimate(0.2, 1_000_000_000L);
        e.recordSuccess(500_000_000, 0);
        e.recordSuccess(100_000_000, 1_000_000_001L);
        assertEquals(100.0, e.snapshot(1_000_000_001L).ewmaMs());
        assertEquals(2, e.snapshot(1_000_000_001L).samples());
        e.recordSuccess(200_000_000, 1_100_000_000L);
        assertEquals(120.0, e.snapshot(1_100_000_000L).ewmaMs());
    }

    @Test void sampleAtExactFreshnessBoundaryStillBlends() {
        var e = new LatencyEstimate(0.2, 1_000_000_000L);
        e.recordSuccess(100_000_000, 0);
        e.recordSuccess(200_000_000, 1_000_000_000L);
        assertEquals(120.0, e.snapshot(1_000_000_000L).ewmaMs());
        assertThrows(IllegalArgumentException.class, () -> new LatencyEstimate(0.2, 0));
    }

    @Test void unknownIsNotReportedAsZeroLatency() {
        var s = new LatencyEstimate(0.2).snapshot(0);
        assertNull(s.ewmaMs());
        assertNull(s.ageMs());
        assertEquals(0, s.samples());
    }

    @Test void computesWeightedLatencyAndMonotonicAge() {
        var e = new LatencyEstimate(0.2);
        e.recordSuccess(100_000_000, 1_000_000_000);
        e.recordSuccess(200_000_000, 2_000_000_000);
        var s = e.snapshot(2_250_000_000L);
        assertEquals(120.0, s.ewmaMs(), 0.00001);
        assertEquals(2, s.samples());
        assertEquals(250L, s.ageMs());
        // Previously returned evidence must remain unchanged after a new observation.
        e.recordSuccess(10_000_000, 3_000_000_000L);
        assertEquals(120.0, s.ewmaMs(), 0.00001);
    }

    @Test void rejectsInvalidConfigurationAndDurations() {
        for (double alpha : new double[]{0, -1, 1.1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new LatencyEstimate(alpha));
        }
        var e = new LatencyEstimate(1);
        assertThrows(IllegalArgumentException.class, () -> e.recordSuccess(-1, 0));
        assertEquals(0, e.snapshot(0).samples());
    }
}
