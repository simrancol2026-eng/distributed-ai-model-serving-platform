package com.dhir.spring_boot.inference;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Compiled-class harness, not a live worker.
 * An artificial Error must keep the schedule running and count as a failed observation.
 */
public class ErrorCountsAsFailure {
    public static void main(String[] args) throws Exception {
        var calls = new AtomicInteger();
        var h = new WorkerHealth(() -> {
            calls.incrementAndGet();
            throw new Error("artificial Error from probe");
        }, System::nanoTime, WorkerPool.healthFreshnessNanos(200, 200));
        var poller = new HealthPoller(List.of(h), 200);
        try {
            Thread.sleep(1200);
            System.out.println("ERROR_CALLS=" + calls.get());
            System.out.println("ERROR_LIVE=" + h.snapshot().live());
            System.out.println("ERROR_FAILURES=" + h.snapshot().failures());
            System.out.println("ERROR_LATEST=" + h.snapshot().latestProbeSucceeded());
        } finally {
            poller.close();
        }

        var calls2 = new AtomicInteger();
        var h2 = new WorkerHealth(() -> {
            calls2.incrementAndGet();
            throw new IllegalStateException("artificial RuntimeException");
        }, System::nanoTime, WorkerPool.healthFreshnessNanos(200, 200));
        var poller2 = new HealthPoller(List.of(h2), 200);
        try {
            Thread.sleep(1200);
            System.out.println("RUNTIME_CALLS=" + calls2.get());
            System.out.println("RUNTIME_FAILURES=" + h2.snapshot().failures());
            System.out.println("RUNTIME_LIVE=" + h2.snapshot().live());
        } finally {
            poller2.close();
        }

        var step = new AtomicInteger();
        var h3 = new WorkerHealth(() -> {
            if (step.getAndIncrement() == 0) {
                return new WorkerHealth.Probe(true, "TEXT", new WorkerClient.ServedModel("model", "rev"));
            }
            throw new Error("artificial Error after one success");
        }, () -> 0L, WorkerPool.healthFreshnessNanos(2000, 2000));
        h3.pollOnce();
        System.out.println("AFTER_SUCCESS failures=" + h3.snapshot().failures() + " live=" + h3.snapshot().live());
        h3.pollOnce();
        System.out.println("AFTER_ONE_ERROR failures=" + h3.snapshot().failures() + " live=" + h3.snapshot().live()
                + " latest=" + h3.snapshot().latestProbeSucceeded());
        h3.pollOnce();
        System.out.println("AFTER_TWO_ERRORS failures=" + h3.snapshot().failures() + " live=" + h3.snapshot().live());
    }
}
