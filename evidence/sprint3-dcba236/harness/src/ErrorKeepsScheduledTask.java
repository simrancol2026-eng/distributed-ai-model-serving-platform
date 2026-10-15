package com.dhir.spring_boot.inference;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Harness only. An artificial Error must not stop the scheduled probe after the repair. */
public class ErrorKeepsScheduledTask {
    public static void main(String[] args) throws Exception {
        var calls = new AtomicInteger();
        var h = new WorkerHealth(() -> {
            int n = calls.incrementAndGet();
            throw new Error("artificial Error from probe " + n);
        }, System::nanoTime, 5_000_000_000L);
        var poller = new HealthPoller(List.of(h), 200);
        try {
            Thread.sleep(1200);
            System.out.println("ERROR_CALLS=" + calls.get());
            System.out.println("ERROR_LIVE=" + h.snapshot().live());
            System.out.println("ERROR_FAILURES=" + h.snapshot().failures());
        } finally {
            poller.close();
        }

        var calls2 = new AtomicInteger();
        var h2 = new WorkerHealth(() -> {
            calls2.incrementAndGet();
            throw new IllegalStateException("artificial RuntimeException");
        }, System::nanoTime, 5_000_000_000L);
        var poller2 = new HealthPoller(List.of(h2), 200);
        try {
            Thread.sleep(1200);
            System.out.println("RUNTIME_CALLS=" + calls2.get());
            System.out.println("RUNTIME_FAILURES=" + h2.snapshot().failures());
            System.out.println("RUNTIME_LIVE=" + h2.snapshot().live());
        } finally {
            poller2.close();
        }
    }
}
