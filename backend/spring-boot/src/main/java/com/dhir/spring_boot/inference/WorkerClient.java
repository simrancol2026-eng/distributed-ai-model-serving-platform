package com.dhir.spring_boot.inference;

import java.time.Duration;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
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
    private final String endpoint;

    /**
     * @param workerUrl already validated by {@link WorkerUrlValidator}
     */
    public WorkerClient(String workerUrl, long connectTimeoutMs, long readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.restClient = RestClient.builder().baseUrl(workerUrl).requestFactory(factory).build();
        this.endpoint = WorkerUrlValidator.endpoint(workerUrl);
    }

    /** host:port of this worker, safe to log and return (no path, no credentials). */
    public String endpoint() {
        return endpoint;
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
            // Anything else from the HTTP client, e.g. a response body that is not the expected JSON.
            throw new WorkerErrorException("Worker at " + endpoint + " response could not be read: " + e.getMessage(), e);
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
