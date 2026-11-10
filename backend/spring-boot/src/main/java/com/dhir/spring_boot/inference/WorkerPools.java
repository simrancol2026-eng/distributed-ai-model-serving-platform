package com.dhir.spring_boot.inference;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * One {@link WorkerPool} per capability (specialist models, D-049).
 *
 * The default pool is the existing Spring bean configured by platform.worker.urls / platform.worker.capability
 * (TEXT): its configuration and behaviour are unchanged. Optional extra pools serve other capabilities:
 *   platform.worker.code.urls   (env PLATFORM_CODE_WORKER_URLS)   -> CODE  workers (WORKER_TASK=code)
 *   platform.worker.image.urls  (env PLATFORM_IMAGE_WORKER_URLS)  -> IMAGE workers (WORKER_TASK=image)
 * Each extra pool is a normal WorkerPool with the same timeouts, routing policy and failover setting, so
 * every Sprint 2-4 routing behaviour applies to it as well. An unset or blank list means that capability
 * has no workers: requests for it get 422 NO_ELIGIBLE_WORKER exactly as before.
 */
@Component
public class WorkerPools {

    private static final Logger log = LoggerFactory.getLogger(WorkerPools.class);

    private final WorkerPool defaultPool;
    private final Map<String, WorkerPool> pools;
    private final List<WorkerPool> owned = new ArrayList<>();

    /** Only the default pool (tests and the pre-specialist configuration). */
    public static WorkerPools of(WorkerPool defaultPool) {
        return new WorkerPools(defaultPool, Map.of());
    }

    /** Explicit pools, e.g. for tests. The default pool's capability must not appear in extra. */
    WorkerPools(WorkerPool defaultPool, Map<String, WorkerPool> extra) {
        this.defaultPool = defaultPool;
        Map<String, WorkerPool> all = new LinkedHashMap<>();
        all.put(defaultPool.capability(), defaultPool);
        extra.forEach((capability, pool) -> {
            if (all.putIfAbsent(capability, pool) != null) {
                throw new IllegalStateException("Invalid configuration: capability " + capability
                        + " is configured twice.");
            }
        });
        this.pools = Collections.unmodifiableMap(all);
    }

    @Autowired
    public WorkerPools(WorkerPool defaultPool,
            @Value("${platform.worker.code.urls:}") String codeUrls,
            @Value("${platform.worker.image.urls:}") String imageUrls,
            @Value("${platform.worker.connect-timeout-ms}") long connectTimeoutMs,
            @Value("${platform.worker.read-timeout-ms}") long readTimeoutMs,
            @Value("${platform.worker.probe-timeout-ms:2000}") long probeTimeoutMs,
            @Value("${platform.worker.identity-max-age-ms:30000}") long identityMaxAgeMs,
            @Value("${platform.routing.policy:ROUND_ROBIN}") String policy,
            FailoverSettings failover) {
        this(defaultPool, extraPools(defaultPool, Map.of("CODE", codeUrls, "IMAGE", imageUrls),
                connectTimeoutMs, readTimeoutMs, probeTimeoutMs, identityMaxAgeMs, policy, failover));
        for (WorkerPool pool : pools.values()) {
            if (pool != defaultPool) owned.add(pool);
        }
        log.info("capabilities: {}", pools.keySet());
    }

    private static Map<String, WorkerPool> extraPools(WorkerPool defaultPool, Map<String, String> urlsByCapability,
            long connectTimeoutMs, long readTimeoutMs, long probeTimeoutMs, long identityMaxAgeMs, String policy,
            FailoverSettings failover) {
        Map<String, WorkerPool> extra = new LinkedHashMap<>();
        for (String capability : List.of("CODE", "IMAGE")) { // fixed order for logs and GET /api/capabilities
            String urls = urlsByCapability.get(capability);
            if (urls == null || urls.isBlank()) continue;
            if (capability.equals(defaultPool.capability())) {
                throw new IllegalStateException("Invalid configuration: platform.worker." + capability.toLowerCase(Locale.ROOT)
                        + ".urls is set but " + capability + " is already the default platform.worker.capability.");
            }
            extra.put(capability, new WorkerPool(urls, capability, connectTimeoutMs, readTimeoutMs,
                    probeTimeoutMs, identityMaxAgeMs, policy, failover));
        }
        return extra;
    }

    /** Pool for an explicit capability (upper case), or null if no worker serves it. AUTO = default pool. */
    public WorkerPool forCapability(String capability) {
        return "AUTO".equals(capability) ? defaultPool : pools.get(capability);
    }

    public WorkerPool defaultPool() {
        return defaultPool;
    }

    /** Configured capabilities, default first. */
    public Map<String, WorkerPool> all() {
        return pools;
    }

    /** Comma-separated configured capabilities, for error messages. */
    public String describe() {
        return String.join(", ", pools.keySet());
    }

    @jakarta.annotation.PreDestroy
    public void close() {
        owned.forEach(WorkerPool::close); // the default pool is a bean and closes itself
    }
}
