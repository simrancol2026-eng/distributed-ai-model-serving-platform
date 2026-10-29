package com.dhir.spring_boot.inference;

/** Budget exhaustion; only a worker given a fair allocation receives timeout feedback. */
final class BudgetExpiredException extends WorkerClient.WorkerUnavailableException {
    private final boolean fairAllocation;
    BudgetExpiredException() { this(false); }
    BudgetExpiredException(boolean fairAllocation) {
        super("Worker dispatch budget exhausted", null);
        this.fairAllocation = fairAllocation;
    }
    boolean fairAllocation() { return fairAllocation; }
}
