package com.dhir.spring_boot.inference;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.json.JsonMapper;
import com.dhir.spring_boot.inference.InferenceDtos.WorkerPrediction;

/** Opt-in transport: bounds waiting for connection, headers AND body with the remaining budget. */
final class BudgetedWorkerTransport {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @FunctionalInterface
    interface Sender {
        java.util.concurrent.CompletableFuture<HttpResponse<String>> send(HttpRequest request);
    }
    private final Sender sender;
    private final URI predictUri;
    private final long readTimeoutMs;

    BudgetedWorkerTransport(HttpClient client, URI predictUri, long readTimeoutMs) {
        this(request -> client.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)),
                predictUri, readTimeoutMs);
    }

    BudgetedWorkerTransport(Sender sender, URI predictUri, long readTimeoutMs) {
        this.sender = sender;
        this.predictUri = predictUri;
        this.readTimeoutMs = readTimeoutMs;
    }

    WorkerPrediction predict(String text, DispatchBudget budget) {
        if (Thread.currentThread().isInterrupted()) {
            throw new WorkerClient.WorkerUnavailableException("Dispatch interrupted", new InterruptedException());
        }
        String body = JSON.writeValueAsString(Map.of("text", text));
        Duration timeout = budget.cappedTimeout(readTimeoutMs);
        boolean requestBudgetLimited = timeout.compareTo(Duration.ofMillis(readTimeoutMs)) < 0;
        if (timeout.isZero()) throw new BudgetExpiredException();
        HttpRequest request = HttpRequest.newBuilder(predictUri).timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        java.util.concurrent.CompletableFuture<HttpResponse<String>> future;
        try { future = sender.send(request); }
        catch (RuntimeException failed) {
            throw new WorkerClient.WorkerUnavailableException("Worker transport could not start", failed);
        }
        try {
            // Re-read after request setup: no fresh allowance for the second attempt or body consumption.
            long budgetRemaining = budget.remaining().toNanos();
            long remaining = Math.min(timeout.toNanos(), budgetRemaining);
            if (remaining <= 0) { future.cancel(true); throw new BudgetExpiredException(); }
            HttpResponse<String> response = future.get(remaining, TimeUnit.NANOSECONDS);
            if (budget.remaining().isZero()) throw new BudgetExpiredException();
            if (response.statusCode() >= 400) {
                throw new WorkerClient.WorkerErrorException("Worker returned HTTP " + response.statusCode(),
                        new RestClientResponseException("Worker HTTP failure", response.statusCode(), "",
                                null, new byte[0], StandardCharsets.UTF_8));
            }
            WorkerPrediction prediction;
            try { prediction = JSON.readValue(response.body(), WorkerPrediction.class); }
            catch (RuntimeException malformed) {
                throw new WorkerClient.WorkerErrorException("Worker response could not be read", malformed);
            }
            if (prediction == null || prediction.label() == null) {
                throw new WorkerClient.WorkerErrorException("Worker returned an empty or malformed prediction");
            }
            if (budget.remaining().isZero()) throw new BudgetExpiredException();
            return prediction;
        } catch (TimeoutException timeoutFailure) {
            future.cancel(true);
            if (requestBudgetLimited || budget.remaining().isZero()) throw new BudgetExpiredException();
            throw readTimeout();
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new WorkerClient.WorkerUnavailableException("Dispatch interrupted", interrupted);
        } catch (ExecutionException failed) {
            // A shorter configured connect timeout is still a real transport timeout.
            if (requestBudgetLimited && failed.getCause() instanceof HttpTimeoutException
                    && !(failed.getCause() instanceof java.net.http.HttpConnectTimeoutException)) {
                throw new BudgetExpiredException();
            }
            throw new WorkerClient.WorkerUnavailableException("Worker transport failed", failed.getCause());
        }
    }

    private static WorkerClient.WorkerUnavailableException readTimeout() {
        return new WorkerClient.WorkerUnavailableException("Worker read timeout expired",
                new HttpTimeoutException("Worker read timeout expired"));
    }
}
