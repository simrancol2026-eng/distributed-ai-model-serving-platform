package com.dhir.spring_boot.inference;

import java.time.Duration;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.dhir.spring_boot.inference.InferenceDtos.WorkerPrediction;

/**
 * HTTP client for ONE Python model worker process ("talk to one worker").
 * Which worker handles a request is decided by {@link WorkerPool}; this class does not choose.
 * Not a Spring bean: WorkerPool creates one WorkerClient per configured URL.
 */
public class WorkerClient {

    private final RestClient restClient;
    private final RestClient probeClient;
    private final String endpoint;
    private final BudgetedWorkerTransport budgetedTransport;

    /** What this worker reported serving on GET /health: artifact (model name) and loaded revision. */
    public record ServedModel(String artifact, String revision) {
    }

    // Last identity reported by the worker, and when (System.nanoTime). Null = unknown.
    private volatile ServedModel served;
    private volatile long servedAtNanos;

    /** Injectable HTTP transports for deterministic tests without opening sockets. */
    WorkerClient(String workerUrl, RestClient restClient, RestClient probeClient) {
        this.endpoint = WorkerUrlValidator.endpoint(workerUrl);
        this.restClient = java.util.Objects.requireNonNull(restClient);
        this.probeClient = java.util.Objects.requireNonNull(probeClient);
        this.budgetedTransport = null;
    }

    /**
     * @param workerUrl already validated by {@link WorkerUrlValidator}
     */
    public WorkerClient(String workerUrl, long connectTimeoutMs, long readTimeoutMs) {
        this(workerUrl, connectTimeoutMs, readTimeoutMs, Math.min(2000, readTimeoutMs));
    }

    /**
     * @param probeTimeoutMs read timeout for the short GET /health identity probe
     */
    public WorkerClient(String workerUrl, long connectTimeoutMs, long readTimeoutMs, long probeTimeoutMs) {
        // JDK HttpClient: a connect timeout and a read timeout both surface as HttpTimeoutException,
        // which Spring reports as ResourceAccessException -> 503. (With the previous
        // HttpURLConnection-based factory a stalled worker could surface as a generic read error -> 502;
        // Grok TEST REPORT on 133dbe9.) HTTP/1.1 explicitly: no h2c upgrade attempt to uvicorn.
        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.restClient = RestClient.builder().baseUrl(workerUrl).requestFactory(factory).build();
        JdkClientHttpRequestFactory probeFactory = new JdkClientHttpRequestFactory(httpClient);
        probeFactory.setReadTimeout(Duration.ofMillis(probeTimeoutMs));
        this.probeClient = RestClient.builder().baseUrl(workerUrl).requestFactory(probeFactory).build();
        this.endpoint = WorkerUrlValidator.endpoint(workerUrl);
        this.budgetedTransport = new BudgetedWorkerTransport(httpClient,
                java.net.URI.create(workerUrl.replaceAll("/+$", "") + "/predict"), readTimeoutMs);
    }

    /** host:port of this worker, safe to log and return (no path, no credentials). */
    public String endpoint() {
        return endpoint;
    }

    /** Uncached liveness and identity from one response, for background health routing. */
    WorkerHealth.Probe probeHealth() {
        Map<?, ?> health = probeClient.get().uri("/health").retrieve().body(Map.class);
        if (health == null) return new WorkerHealth.Probe(false, null, null);
        String capability = health.get("capability") instanceof String c ? c : null;
        ServedModel identity = null;
        if (health.get("model") instanceof String m && !m.isBlank()
                && health.get("revision") instanceof String r && !r.isBlank()) {
            identity = new ServedModel(m, r);
        }
        return new WorkerHealth.Probe("healthy".equals(health.get("status")), capability, identity);
    }

    /**
     * The (artifact, revision) this worker says it serves, from its GET /health.
     * A cached answer younger than maxAgeMs is reused; otherwise the worker is asked again.
     * If the probe fails, the last known answer is kept (a worker that is down is NOT silently
     * dropped: requests routed to it still fail with 503, no failover). Empty = never known, or the
     * worker does not report a revision: such a worker is not used for version-specific requests.
     */
    public java.util.Optional<ServedModel> servedModel(long maxAgeMs) {
        ServedModel current = served;
        if (current != null && System.nanoTime() - servedAtNanos < maxAgeMs * 1_000_000L) {
            return java.util.Optional.of(current);
        }
        try {
            Map<?, ?> health = probeClient.get().uri("/health").retrieve().body(Map.class);
            Object model = health == null ? null : health.get("model");
            Object revision = health == null ? null : health.get("revision");
            if (model instanceof String m && revision instanceof String r && !m.isBlank() && !r.isBlank()) {
                served = new ServedModel(m, r);
                servedAtNanos = System.nanoTime();
            } else {
                served = null; // reachable, but does not say which revision it loaded
            }
        } catch (RestClientException e) {
            // Unreachable or unreadable right now: keep the last known identity (may be null).
        }
        return java.util.Optional.ofNullable(served);
    }

    /** Forget the cached identity (used when a response contradicts it). */
    public void forgetServedModel() {
        served = null;
    }

    /**
     * Sends text to the worker's POST /predict.
     *
     * @throws WorkerUnavailableException if the worker cannot be reached or times out
     * @throws WorkerErrorException       if the worker answers with an HTTP error status
     */
    public WorkerPrediction predict(String text) {
        try {
            WorkerPrediction prediction = restClient.post()
                    .uri("/predict")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("text", text))
                    .retrieve()
                    .body(WorkerPrediction.class);
            if (prediction == null || prediction.label() == null) {
                throw new WorkerErrorException("Worker returned an empty or malformed prediction");
            }
            return prediction;
        } catch (ResourceAccessException e) {
            // Connection refused, DNS failure, or timeout: the worker is not usable right now.
            throw new WorkerUnavailableException(
                    "Worker at " + endpoint + " is unavailable: " + e.getMessage(), e);
        } catch (RestClientResponseException e) {
            throw new WorkerErrorException(
                    "Worker at " + endpoint + " returned HTTP " + e.getStatusCode().value(), e);
        } catch (RestClientException e) {
            // A worker that accepts the connection but never answers hits the READ timeout. Depending on
            // where it fires, the HTTP client reports that as a generic RestClientException (e.g. while
            // reading the body), not as ResourceAccessException. It is still "worker not usable" -> 503.
            if (isTimeout(e)) {
                throw new WorkerUnavailableException(
                        "Worker at " + endpoint + " timed out: " + e.getMessage(), e);
            }
            // Anything else from the HTTP client, e.g. a response body that is not the expected JSON.
            throw new WorkerErrorException("Worker at " + endpoint + " response could not be read: " + e.getMessage(), e);
        }
    }

    /** Opt-in bounded dispatch. The original predict method remains unchanged when failover is off. */
    WorkerPrediction predict(String text, DispatchBudget budget) {
        if (budgetedTransport == null) {
            throw new IllegalStateException("A budget-capable transport is required for failover");
        }
        return budgetedTransport.predict(text, budget);
    }

    /** True if a socket/read timeout appears anywhere in the cause chain. */
    static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = (t.getCause() == t ? null : t.getCause())) {
            if (t instanceof java.net.SocketTimeoutException || t instanceof java.net.http.HttpTimeoutException) {
                return true;
            }
        }
        return false;
    }

    public static class WorkerUnavailableException extends RuntimeException {
        public WorkerUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class WorkerErrorException extends RuntimeException {
        public WorkerErrorException(String message) {
            super(message);
        }

        public WorkerErrorException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
