package com.dhir.spring_boot.inference;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class HealthRoutingTest {
    final AtomicLong now = new AtomicLong();
    final WorkerClient a = worker(8001), b = worker(8002);
    final AtomicReference<WorkerHealth.Probe> probeA = new AtomicReference<>(good("rev"));
    final AtomicReference<WorkerHealth.Probe> probeB = new AtomicReference<>(good("rev"));
    final WorkerHealth ha = new WorkerHealth(probeA::get, now::get, 5_000_000_000L);
    final WorkerHealth hb = new WorkerHealth(probeB::get, now::get, 5_000_000_000L);
    final HealthRouting router = new HealthRouting(List.of(a, b), Map.of(a, ha, b, hb), now::get);

    static WorkerClient worker(int port) {
        var transport = (RestClient) java.lang.reflect.Proxy.newProxyInstance(RestClient.class.getClassLoader(),
                new Class<?>[]{RestClient.class}, (proxy, method, args) -> {
                    throw new AssertionError("Routing must not call HTTP transport");
                });
        return new WorkerClient("http://localhost:" + port, transport, transport);
    }
    static WorkerHealth.Probe good(String revision) {
        return new WorkerHealth.Probe(true, "TEXT", new WorkerClient.ServedModel("model", revision));
    }
    void poll() { ha.pollOnce(); hb.pollOnce(); }
    HealthRouting.Choice choose() { return router.choose("TEXT", "model", "rev", Map.of()); }

    @Test void unknownAndStaleReturn503WhileProvenMismatchReturns422() {
        assertEquals("NO_HEALTHY_WORKER", choose().error());
        probeA.set(good("other")); probeB.set(good("other")); poll();
        assertEquals("NO_ELIGIBLE_WORKER", choose().error());
        now.set(5_000_000_001L);
        assertEquals("NO_HEALTHY_WORKER", choose().error());
    }
    @Test void exactRevisionAndCapabilityCannotBeWidened() {
        probeA.set(good("other")); poll();
        assertSame(b, choose().worker());
        probeB.set(new WorkerHealth.Probe(true, "IMAGE", good("rev").identity())); poll();
        assertEquals("NO_ELIGIBLE_WORKER", choose().error());
    }
    @Test void rotatesTiesAndUsesAtomicCountSnapshotForScores() {
        poll();
        assertSame(a, choose().worker());
        assertSame(b, choose().worker());
        var choice = router.choose("TEXT", null, null, Map.of(a, 2));
        assertSame(b, choice.worker());
        assertEquals(300.0, choice.candidates().get(0).evidence().score());
    }
    @Test void measuredLatencyIsUsedAndStaleSamplesBecomeOptimistic() {
        poll(); router.success(a, 100_000_000); router.success(b, 10_000_000);
        assertSame(b, choose().worker());
        now.set(31_000_000_000L); poll();
        var choice = choose();
        assertEquals(100.0, choice.candidates().get(0).evidence().estimateMs());
        assertEquals(100.0, choice.candidates().get(1).evidence().estimateMs());
        assertEquals(100.0, choice.candidates().get(0).evidence().ewmaMs());
    }
    @Test void cooldownExcludesFailureWithoutRecordingFastFailureAsLatency() {
        poll(); router.failure(a, true, false);
        var choice = choose();
        assertSame(b, choice.worker());
        assertEquals("COOLDOWN", choice.candidates().get(0).note());
        assertNull(choice.candidates().get(0).evidence().ewmaMs());
        assertEquals(0, choice.candidates().get(0).evidence().samples());
        now.set(5_000_000_001L); poll();
        assertEquals(0, choose().candidates().get(0).evidence().cooldownMs());
    }
    @Test void onlyRepeatedServerErrorsCoolDownAndOverrideNeverAdmitsUnhealthyWorker() {
        poll(); router.failure(a, false, true);
        assertEquals(0, choose().candidates().get(0).evidence().cooldownMs());
        router.failure(a, false, true);
        router.failure(b, true, false);
        assertTrue(choose().reason().startsWith("COOLDOWN_OVERRIDE"));
        probeA.set(null); ha.pollOnce(); ha.pollOnce();
        assertSame(b, choose().worker());
        assertFalse(choose().candidates().get(0).eligible());
    }
    @Test void mismatchInvalidatesIdentityAndNoModelStillRequiresLiveHealth() {
        poll(); router.mismatch(a);
        assertSame(b, choose().worker());
        router.mismatch(b);
        assertEquals("NO_HEALTHY_WORKER", router.choose("TEXT", null, null, Map.of()).error());
    }
}
