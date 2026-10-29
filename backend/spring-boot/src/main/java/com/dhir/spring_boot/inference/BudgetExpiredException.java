package com.dhir.spring_boot.inference;

/** The caller's dispatch allowance expired; this is not evidence of an unhealthy worker. */
final class BudgetExpiredException extends WorkerClient.WorkerUnavailableException {
    BudgetExpiredException() { super("Worker dispatch budget exhausted", null); }
}
