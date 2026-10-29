package com.dhir.spring_boot.inference;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class DispatchBudgetTest {
    @Test void selectionAndBothAttemptsConsumeSameBudget() {
        AtomicLong clock = new AtomicLong();
        DispatchBudget budget = new DispatchBudget(10000, clock::get);
        clock.addAndGet(1_000_000_000L); // selection
        assertEquals(Duration.ofSeconds(9), budget.cappedTimeout(30000));
        clock.addAndGet(8_000_000_000L); // first attempt
        assertEquals(Duration.ofSeconds(1), budget.cappedTimeout(30000));
        clock.addAndGet(1_000_000_001L);
        assertTrue(budget.remaining().isZero());
    }

    @Test void preservesSubMillisecondRemainderAndConfiguredCap() {
        AtomicLong clock = new AtomicLong();
        DispatchBudget budget = new DispatchBudget(10, clock::get);
        assertEquals(Duration.ofMillis(2), budget.cappedTimeout(2));
        clock.set(9_999_999L);
        assertEquals(Duration.ofNanos(1), budget.remaining());
    }

    @Test void monotonicCounterWraparound() {
        AtomicLong clock = new AtomicLong(Long.MAX_VALUE - 500_000L);
        DispatchBudget budget = new DispatchBudget(2, clock::get);
        clock.addAndGet(1_000_000L);
        assertEquals(Duration.ofMillis(1), budget.remaining());
    }

    @Test void invalidBudgetsCannotOverflow() {
        for (long value : new long[] {0, -1, Long.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> new DispatchBudget(value));
        }
    }
}
