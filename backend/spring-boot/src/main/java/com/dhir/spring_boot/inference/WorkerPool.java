package com.dhir.spring_boot.inference;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The configured model workers and the round-robin choice between them (Sprint 2).
 *
 * Configuration: platform.worker.urls, a comma-separated list
 * (environment variable PLATFORM_WORKER_URLS). If it is not set it falls back to the single
 * platform.worker.url (PLATFORM_WORKER_URL), so the one-worker setup behaves exactly as before.
 *
 * Round-robin: each call to {@link #next()} returns the next worker in list order
 * (A, B, A, B, ...). The position is an AtomicLong, so concurrent requests never get the
 * same turn and the order stays fair under load.
 *
 * No failover: if the chosen worker is down the request fails (503) and the NEXT request
 * simply moves on to the next worker. Retrying on another worker is not implemented.
 *
 * All workers are assumed to serve the same capability (platform.worker.capability).
 */
@Component
public class WorkerPool {

    private static final Logger log = LoggerFactory.getLogger(WorkerPool.class);

    private final List<WorkerClient> workers;
    private final String capability;
    private final AtomicLong position = new AtomicLong();
    /** Separate round-robin position per served (artifact, revision), for version-specific requests. */
    private final ConcurrentHashMap<String, AtomicLong> versionPositions = new ConcurrentHashMap<>();
    private final long identityMaxAgeMs;

    public WorkerPool(String urls, String capability, long connectTimeoutMs, long readTimeoutMs) {
        this(urls, capability, connectTimeoutMs, readTimeoutMs, 2000, 30000);
    }

    @Autowired
    public WorkerPool(
            @Value("${platform.worker.urls}") String urls,
            @Value("${platform.worker.capability}") String capability,
            @Value("${platform.worker.connect-timeout-ms}") long connectTimeoutMs,
            @Value("${platform.worker.read-timeout-ms}") long readTimeoutMs,
            @Value("${platform.worker.probe-timeout-ms:2000}") long probeTimeoutMs,
            @Value("${platform.worker.identity-max-age-ms:30000}") long identityMaxAgeMs) {
        List<String> validated = parse(urls);
        List<WorkerClient> clients = new ArrayList<>();
        for (String url : validated) {
            clients.add(new WorkerClient(url, connectTimeoutMs, readTimeoutMs, probeTimeoutMs));
        }
        this.workers = List.copyOf(clients);
        this.capability = capability;
        this.identityMaxAgeMs = identityMaxAgeMs;
        log.info("worker pool: {} worker(s), round-robin order {}", workers.size(), endpoints());
    }

    /**
     * Parses and validates the comma-separated URL list. Fails at startup (IllegalStateException)
     * on an empty list, an empty entry, an invalid URL, or the same worker listed twice.
     */
    static List<String> parse(String urls) {
        if (urls == null || urls.isBlank()) {
            // Reuse the validator's message for a blank value.
            WorkerUrlValidator.validate("platform.worker.urls", urls);
        }
        String[] parts = urls.split(",", -1);
        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < parts.length; i++) {
            String property = "platform.worker.urls[" + i + "]";
            String url = WorkerUrlValidator.validate(property, parts[i]);
            if (!seen.add(WorkerUrlValidator.identity(url))) {
                throw new IllegalStateException("Invalid configuration: " + property + "='" + url
                        + "' is listed more than once. Each entry must be a separate worker process.");
            }
            result.add(url);
        }
        return result;
    }

    /** The worker that should serve the next request (round-robin). Thread-safe. */
    public WorkerClient next() {
        int index = (int) Math.floorMod(position.getAndIncrement(), (long) workers.size());
        return workers.get(index);
    }

    /**
     * Workers whose own /health reports exactly this artifact and revision, in configured order.
     * Workers with an unknown identity are excluded (fail closed).
     */
    public List<WorkerClient> eligible(String artifact, String revision) {
        WorkerClient.ServedModel wanted = new WorkerClient.ServedModel(artifact, revision);
        return workers.stream()
                .filter(w -> w.servedModel(identityMaxAgeMs).map(wanted::equals).orElse(false))
                .toList();
    }

    /** Round-robin among the given eligible workers, fair per (artifact, revision). Thread-safe. */
    public WorkerClient next(List<WorkerClient> eligible, String artifact, String revision) {
        AtomicLong pos = versionPositions.computeIfAbsent(artifact + "@" + revision, k -> new AtomicLong());
        int index = (int) Math.floorMod(pos.getAndIncrement(), (long) eligible.size());
        return eligible.get(index);
    }

    public String capability() {
        return capability;
    }

    public int size() {
        return workers.size();
    }

    /** host:port of each worker, in round-robin order. */
    public List<String> endpoints() {
        return workers.stream().map(WorkerClient::endpoint).toList();
    }
}
