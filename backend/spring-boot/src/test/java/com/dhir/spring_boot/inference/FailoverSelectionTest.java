package com.dhir.spring_boot.inference;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class FailoverSelectionTest {
    @Test void freshnessIdentityAndExclusionForEveryPolicy() {
        for (var policy : WorkerPool.Policy.values()) {
            var now = new AtomicLong();
            var a = HealthRoutingTest.worker(8001); var b = HealthRoutingTest.worker(8002);
            long freshness = WorkerPool.healthFreshnessNanos(2000, 2000);
            var ha = new WorkerHealth(() -> HealthRoutingTest.good("other"), now::get, freshness);
            var hb = new WorkerHealth(() -> HealthRoutingTest.good("rev"), now::get, freshness);
            var pool = new WorkerPool(List.of(a,b), policy, Map.of(a,ha,b,hb));
            assertEquals("NO_FRESH_OBSERVATION", pool.reserveFailover(null,null,Set.of()).error());
            hb.pollOnce();
            try (var r = pool.reserveFailover("model","rev",Set.of()).reservation()) { assertSame(b,r.worker()); }
            ha.pollOnce(); now.set(9_000_000_000L);
            try (var r = pool.reserveFailover("model","rev",Set.of()).reservation()) { assertSame(b,r.worker()); }
            assertNull(pool.reserveFailover("model","rev",Set.of(b.endpoint())).reservation());
            assertEquals("NO_ELIGIBLE_WORKER", pool.reserveFailover("model","missing",Set.of()).error());
            now.set(11_000_000_000L);
            assertEquals("NO_FRESH_OBSERVATION", pool.reserveFailover(null,null,Set.of()).error());
            assertTrue(pool.counts().values().stream().allMatch(n -> n == 0));
        }
        assertEquals(16_000_000_000L, WorkerPool.healthFreshnessNanos(2000,5000));
    }

    @Test void fiftyConcurrentRequestsReleaseEveryReservation() throws Exception {
        for (var policy : WorkerPool.Policy.values()) {
            var a = HealthRoutingTest.worker(8001); var b = HealthRoutingTest.worker(8002);
            var ha = new WorkerHealth(() -> HealthRoutingTest.good("rev"), System::nanoTime, 60_000_000_000L);
            var hb = new WorkerHealth(() -> HealthRoutingTest.good("rev"), System::nanoTime, 60_000_000_000L);
            ha.pollOnce(); hb.pollOnce();
            var pool = new WorkerPool(List.of(a,b),policy,Map.of(a,ha,b,hb));
            var start = new CountDownLatch(1);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var tasks = new ArrayList<Future<?>>();
                for (int i=0;i<50;i++) tasks.add(executor.submit(() -> {
                    try { start.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
                    String first;
                    try (var r = pool.reserveFailover("model","rev",Set.of()).reservation()) {
                        assertNotNull(r); first=r.worker().endpoint(); pool.predictionFailed(r.worker(),true,false);
                    }
                    try (var r = pool.reserveFailover("model","rev",Set.of(first)).reservation()) {
                        assertNotNull(r); assertNotEquals(first,r.worker().endpoint());
                    }
                }));
                start.countDown();
                for (var task:tasks) task.get(10,TimeUnit.SECONDS);
            }
            assertTrue(pool.counts().values().stream().allMatch(n -> n == 0));
        }
    }
}
