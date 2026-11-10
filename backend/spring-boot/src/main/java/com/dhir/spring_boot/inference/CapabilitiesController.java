package com.dhir.spring_boot.inference;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/capabilities (specialist models, D-049): which capabilities this backend is configured to serve,
 * and what each configured worker reports RIGHT NOW on its own GET /health.
 *
 * Every worker is probed live on each call (short probe timeout, platform.worker.probe-timeout-ms), so nothing
 * here is cached or invented: a worker that does not answer is reported with reachable=false and null model
 * fields. Read-only; it never changes routing state.
 */
@RestController
public class CapabilitiesController {

    private final WorkerPools pools;

    public CapabilitiesController(WorkerPools pools) {
        this.pools = pools;
    }

    /** One configured worker as it describes itself. reportedCapability comes from the worker, not config. */
    public record WorkerView(String endpoint, boolean reachable, boolean healthy, String reportedCapability,
            String model, String revision) {
    }

    public record CapabilityView(String capability, String policy, int workerCount, int healthyCount,
            List<WorkerView> workers) {
    }

    public record CapabilitiesResponse(String defaultCapability, List<CapabilityView> capabilities) {
    }

    @GetMapping("/api/capabilities")
    public CapabilitiesResponse capabilities() {
        List<CapabilityView> result = new ArrayList<>();
        for (Map.Entry<String, WorkerPool> entry : pools.all().entrySet()) {
            WorkerPool pool = entry.getValue();
            List<WorkerView> views = new ArrayList<>();
            int healthy = 0;
            for (WorkerClient worker : pool.all()) {
                WorkerView view = describe(worker);
                // Counted healthy only if it reports healthy AND the capability its pool is configured for.
                if (view.healthy() && entry.getKey().equals(view.reportedCapability())) healthy++;
                views.add(view);
            }
            result.add(new CapabilityView(entry.getKey(), pool.policy(), views.size(), healthy, views));
        }
        return new CapabilitiesResponse(pools.defaultPool().capability(), result);
    }

    private static WorkerView describe(WorkerClient worker) {
        try {
            WorkerHealth.Probe probe = worker.probeHealth();
            WorkerClient.ServedModel identity = probe.identity();
            return new WorkerView(worker.endpoint(), true, probe.healthy(), probe.capability(),
                    identity == null ? null : identity.artifact(), identity == null ? null : identity.revision());
        } catch (RuntimeException e) { // unreachable, timed out, or not a readable /health body
            return new WorkerView(worker.endpoint(), false, false, null, null, null);
        }
    }
}
