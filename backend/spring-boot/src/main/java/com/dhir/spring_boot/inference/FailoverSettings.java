package com.dhir.spring_boot.inference;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Opt-in settings. Disabled requests never receive a dispatch budget. */
@Component
public record FailoverSettings(boolean enabled, long budgetMs) {
    public FailoverSettings(
            @Value("${worker.failover.enabled:false}") boolean enabled,
            @Value("${worker.failover.budget-ms:10000}") long budgetMs) {
        if (budgetMs <= 0 || budgetMs > Long.MAX_VALUE / 1_000_000L) {
            throw new IllegalArgumentException("worker.failover.budget-ms must be positive and representable in nanoseconds");
        }
        this.enabled = enabled;
        this.budgetMs = budgetMs;
    }
}
