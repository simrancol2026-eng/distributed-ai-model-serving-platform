package com.dhir.spring_boot.inference;

import java.time.Duration;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.dhir.spring_boot.inference.InferenceDtos.WorkerPrediction;

/**
 * HTTP client for the single Python model worker (Phase 1).
 * In later phases the routing engine will choose among many workers; this class
 * stays responsible only for "talk to one worker".
 */
@Component
public class WorkerClient {

    private final RestClient restClient;
    private final String workerId;
    private final String workerUrl;
    private final String capability;

    public WorkerClient(
            @Value("${platform.worker.id}") String workerId,
            @Value("${platform.worker.url}") String workerUrl,
            @Value("${platform.worker.capability}") String capability,
            @Value("${platform.worker.connect-timeout-ms}") long connectTimeoutMs,
            @Value("${platform.worker.read-timeout-ms}") long readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.restClient = RestClient.builder().baseUrl(workerUrl).requestFactory(factory).build();
        this.workerId = workerId;
        this.workerUrl = workerUrl;
        this.capability = capability;
    }

    public String workerId() {
        return workerId;
    }

    public String capability() {
        return capability;
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
                    "Worker " + workerId + " at " + workerUrl + " is unavailable: " + e.getMessage(), e);
        } catch (RestClientResponseException e) {
            throw new WorkerErrorException(
                    "Worker " + workerId + " returned HTTP " + e.getStatusCode().value(), e);
        } catch (RestClientException e) {
            // Anything else from the HTTP client, e.g. a response body that is not the expected JSON.
            throw new WorkerErrorException("Worker " + workerId + " response could not be read: " + e.getMessage(), e);
        }
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
