package com.dhir.spring_boot.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * Plain unit tests (no Spring, no network): worker list parsing/validation and the
 * round-robin choice. Creating a WorkerClient opens no connection, so no worker has to run.
 */
class WorkerPoolTest {

    static WorkerPool pool(String urls) {
        return new WorkerPool(urls, "TEXT", 2000, 30000);
    }

    @Test
    void singleUrlIsOneWorker() {
        WorkerPool p = pool("http://localhost:8001");
        assertEquals(1, p.size());
        for (int i = 0; i < 3; i++) {
            assertEquals("localhost:8001", p.next().endpoint());
        }
    }

    @Test
    void twoWorkersAlternateInConfiguredOrder() {
        WorkerPool p = pool(" http://localhost:8001 , http://localhost:8002/ ");
        assertEquals(List.of("localhost:8001", "localhost:8002"), p.endpoints());
        List<String> order = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            order.add(p.next().endpoint());
        }
        assertEquals(List.of("localhost:8001", "localhost:8002", "localhost:8001", "localhost:8002",
                "localhost:8001", "localhost:8002"), order);
    }

    @Test
    void threeWorkersCycle() {
        WorkerPool p = pool("http://a:1,http://b:2,http://c:3");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 7; i++) {
            sb.append(p.next().endpoint().charAt(0));
        }
        assertEquals("abcabca", sb.toString());
    }

    @Test
    void concurrentSelectionIsFairAndLosesNoTurns() throws Exception {
        WorkerPool p = pool("http://localhost:8001,http://localhost:8002");
        int threads = 16;
        int perThread = 1000;
        Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(executor.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    counts.computeIfAbsent(p.next().endpoint(), k -> new AtomicInteger()).incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        executor.shutdown();
        // 16,000 turns over 2 workers: exactly half each, because every turn is a distinct counter value.
        assertEquals(8000, counts.get("localhost:8001").get());
        assertEquals(8000, counts.get("localhost:8002").get());
    }

    @Test
    void rejectsEmptyListAndEmptyEntries() {
        for (String bad : new String[] { "", "   ", "http://localhost:8001,", ",http://localhost:8001",
                "http://localhost:8001,,http://localhost:8002" }) {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> pool(bad), bad);
            assertTrue(e.getMessage().startsWith("Invalid configuration"), e.getMessage());
        }
    }

    @Test
    void rejectsInvalidEntryAndNamesIt() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> pool("http://localhost:8001,localhost:8002"));
        assertTrue(e.getMessage().contains("platform.worker.urls[1]"), e.getMessage());
    }

    @Test
    void rejectsTheSameWorkerListedTwice() {
        for (String dup : new String[] { "http://localhost:8001,http://localhost:8001",
                "http://localhost:8001,http://LOCALHOST:8001/", "http://worker:80,http://worker" }) {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> pool(dup), dup);
            assertTrue(e.getMessage().contains("more than once"), e.getMessage());
        }
    }
}
