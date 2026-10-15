package com.dhir.spring_boot.inference;

import java.util.*;
import java.util.function.LongSupplier;

/** Called under the pool reservation monitor; reads observations but never performs I/O. */
final class HealthRouting {
    record Evidence(boolean live, Long healthAgeMs, Double ewmaMs, long samples,
                    Long sampleAgeMs, Double estimateMs, Double score, long cooldownMs) {}
    record Candidate(WorkerClient worker, boolean eligible, String note,
                     WorkerClient.ServedModel identity, Evidence evidence) {}
    record Choice(WorkerClient worker, List<Candidate> candidates, String error, String reason) {}
    private final List<WorkerClient> workers;
    private final Map<WorkerClient, WorkerHealth> health;
    private final Map<WorkerClient, LatencyEstimate> latencies = new HashMap<>();
    private final Map<WorkerClient, Long> cooldowns = new HashMap<>();
    private final Map<WorkerClient, Integer> serverFailures = new HashMap<>();
    private final Map<String, Integer> cursors = new HashMap<>();
    private final LongSupplier ticker;

    HealthRouting(List<WorkerClient> workers, Map<WorkerClient, WorkerHealth> health, LongSupplier ticker) {
        this.workers = List.copyOf(workers);
        this.health = Map.copyOf(health);
        this.ticker = ticker;
        for (WorkerClient worker : workers) latencies.put(worker, new LatencyEstimate(0.2));
    }

    void success(WorkerClient worker, long durationNanos) {
        latencies.get(worker).recordSuccess(durationNanos, ticker.getAsLong());
        serverFailures.remove(worker);
        cooldowns.remove(worker);
    }

    void failure(WorkerClient worker, boolean transport, boolean serverError) {
        int failures = serverError ? serverFailures.merge(worker, 1, Integer::sum) : 0;
        if (!serverError) serverFailures.remove(worker);
        if (transport || failures >= 2) cooldowns.put(worker, ticker.getAsLong() + 5_000_000_000L);
    }

    void mismatch(WorkerClient worker) { health.get(worker).invalidateIdentity(); }

    Choice choose(String capability, String artifact, String revision, Map<WorkerClient, Integer> counts) {
        long now = ticker.getAsLong();
        var snapshots = new LinkedHashMap<WorkerClient, WorkerHealth.Snapshot>();
        var samples = new HashMap<WorkerClient, LatencyEstimate.Snapshot>();
        var matches = new ArrayList<WorkerClient>();
        var wanted = artifact == null ? null : new WorkerClient.ServedModel(artifact, revision);
        boolean provenAbsent = true;
        double optimistic = Double.POSITIVE_INFINITY;
        for (WorkerClient w : workers) {
            var h = health.get(w).snapshot();
            var latency = latencies.get(w).snapshot(now);
            snapshots.put(w, h);
            samples.put(w, latency);
            boolean identityKnown = wanted == null || h.identity() != null;
            boolean match = capability.equals(h.capability()) && (wanted == null || wanted.equals(h.identity()));
            // Missing identity is uncertainty, not proof that a requested revision is absent.
            if (!h.latestProbeSucceeded() || !identityKnown || match) provenAbsent = false;
            if (h.live() && match) {
                matches.add(w);
                if (latency.ewmaMs() != null && latency.ageMs() <= 30_000) {
                    optimistic = Math.min(optimistic, Math.max(0.001, latency.ewmaMs()));
                }
            }
        }
        if (!Double.isFinite(optimistic)) optimistic = 100;
        var selectable = new ArrayList<WorkerClient>();
        for (WorkerClient w : matches) if (remaining(w, now) == 0) selectable.add(w);
        boolean override = selectable.isEmpty() && !matches.isEmpty();
        if (override) {
            long earliest = matches.stream().mapToLong(w -> remaining(w, now)).min().orElseThrow();
            for (WorkerClient w : matches) if (remaining(w, now) == earliest) selectable.add(w);
        }
        var evidence = new ArrayList<Candidate>();
        var scores = new HashMap<WorkerClient, Double>();
        for (WorkerClient w : workers) {
            var h = snapshots.get(w);
            var l = samples.get(w);
            boolean matching = matches.contains(w);
            double estimate = l.ewmaMs() == null || l.ageMs() > 30_000 ? optimistic : Math.max(0.001, l.ewmaMs());
            double score = (counts.getOrDefault(w, 0) + 1.0) * estimate;
            scores.put(w, score);
            String note = !h.live() ? "UNHEALTHY"
                    : !capability.equals(h.capability()) ? "CAPABILITY_MISMATCH"
                    : wanted != null && h.identity() == null ? "IDENTITY_UNKNOWN"
                    : wanted != null && !wanted.equals(h.identity()) ? "SERVES_OTHER"
                    : !selectable.contains(w) ? "COOLDOWN"
                    : override ? "COOLDOWN_OVERRIDE" : "ELIGIBLE";
            evidence.add(new Candidate(w, selectable.contains(w), note, h.identity(),
                    new Evidence(h.live(), h.ageMs(), l.ewmaMs(), l.samples(), l.ageMs(),
                            matching ? estimate : null, matching ? score : null,
                            remaining(w, now) / 1_000_000L)));
        }
        if (selectable.isEmpty()) {
            String error = provenAbsent ? "NO_ELIGIBLE_WORKER" : "NO_HEALTHY_WORKER";
            return new Choice(null, List.copyOf(evidence), error, error);
        }
        String key = capability + ":" + artifact + "@" + revision;
        int start = Math.floorMod(cursors.getOrDefault(key, 0), workers.size());
        WorkerClient chosen = null;
        int selected = start;
        for (int offset = 0; offset < workers.size(); offset++) {
            int index = (start + offset) % workers.size();
            WorkerClient w = workers.get(index);
            if (selectable.contains(w) && (chosen == null || scores.get(w) < scores.get(chosen))) {
                chosen = w;
                selected = index;
            }
        }
        cursors.put(key, (selected + 1) % workers.size());
        return new Choice(chosen, List.copyOf(evidence), null,
                override ? "COOLDOWN_OVERRIDE: earliest recovery among live eligible workers"
                        : "minimum (inFlight + 1) * estimated successful predict latency");
    }

    private long remaining(WorkerClient worker, long now) {
        Long end = cooldowns.get(worker);
        return end == null ? 0 : Math.max(0, end - now);
    }
}
