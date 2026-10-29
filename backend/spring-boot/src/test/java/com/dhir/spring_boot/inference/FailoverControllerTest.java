package com.dhir.spring_boot.inference;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.sql.SQLException;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import com.dhir.spring_boot.tracking.RequestTracker;
import com.dhir.spring_boot.inference.InferenceDtos.*;

/** Deterministic controller/pool integration with fake workers and unavailable tracking storage. */
class FailoverControllerTest {
    static RestClient unused() {
        return (RestClient) Proxy.newProxyInstance(RestClient.class.getClassLoader(), new Class<?>[]{RestClient.class},
                (p,m,a) -> { throw new AssertionError("Unexpected HTTP"); });
    }
    static class FakeWorker extends WorkerClient {
        int calls;
        RuntimeException failure;
        WorkerPool pool;
        FakeWorker(int port, RuntimeException failure) { super("http://localhost:" + port, unused(), unused()); this.failure = failure; }
        @Override public WorkerPrediction predict(String text) { return predict(text, new DispatchBudget(10000)); }
        @Override WorkerPrediction predict(String text, DispatchBudget budget) {
            calls++;
            assertEquals(1, pool.counts().values().stream().mapToInt(Integer::intValue).sum(),
                    "previous reservation must be released before alternate dispatch");
            if (failure != null) throw failure;
            return new WorkerPrediction(endpoint(), "model", "rev", "sentiment", "POSITIVE", 0.9, 1);
        }
    }
    static RequestTracker tracker() {
        DataSource down = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (p,m,a) -> { throw new SQLException("deliberately unavailable"); });
        return new RequestTracker(down, 1, 10000);
    }
    static WorkerPool pool(FakeWorker... workers) {
        var observations = new LinkedHashMap<WorkerClient, WorkerHealth>();
        for (var w : workers) {
            var health = new WorkerHealth(() -> new WorkerHealth.Probe(true, "TEXT", new WorkerClient.ServedModel("model", "rev")),
                    System::nanoTime, 10_000_000_000L);
            health.pollOnce(); observations.put(w, health);
        }
        WorkerPool pool = new WorkerPool(new ArrayList<WorkerClient>(List.of(workers)), WorkerPool.Policy.ROUND_ROBIN, observations);
        for (var w : workers) w.pool = pool;
        return pool;
    }
    static WorkerClient.WorkerUnavailableException refused() {
        return new WorkerClient.WorkerUnavailableException("refused", new ConnectException());
    }
    static org.springframework.http.ResponseEntity<?> infer(WorkerPool pool) {
        return new InferenceController(pool, null, tracker(), new FailoverSettings(true, 10000))
                .infer(new InferenceRequest("TEXT", "hello", null, null));
    }
    @Test void connectionFailureThenSuccessRecordsBothAndReleases() {
        var a = new FakeWorker(8001, refused()); var b = new FakeWorker(8002, null);
        var pool = pool(a,b);
        var response = infer(pool);
        assertEquals(200, response.getStatusCode().value());
        var body = (InferenceResponse) response.getBody();
        assertEquals(b.endpoint(), body.workerEndpoint());
        assertEquals(2, body.routing().attempts().size());
        assertEquals("SUCCEEDED", body.routing().terminalReason());
        assertTrue(pool.counts().values().stream().allMatch(n -> n == 0));
    }

    @Test void coldStartReturnsFreshnessErrorWithoutInvokingWorkers() {
        var a = new FakeWorker(8001, null);
        var health = new WorkerHealth(() -> HealthRoutingTest.good("rev"), System::nanoTime, 10_000_000_000L);
        var pool = new WorkerPool(List.of(a), WorkerPool.Policy.ROUND_ROBIN, Map.of(a,health));
        a.pool = pool;
        var response = infer(pool);
        var error = (ErrorResponse) response.getBody();
        assertEquals(503, response.getStatusCode().value());
        assertEquals("NO_FRESH_OBSERVATION", error.error());
        assertEquals("NO_FRESH_OBSERVATION", error.routing().terminalReason());
        assertTrue(error.routing().attempts().isEmpty()); assertEquals(0,a.calls);
        health.pollOnce();
        assertEquals(200,infer(pool).getStatusCode().value());
    }
    @Test void timeoutNeverRetries() {
        var a = new FakeWorker(8001, new WorkerClient.WorkerUnavailableException("timeout", new HttpTimeoutException("read")));
        var b = new FakeWorker(8002, null); var pool = pool(a,b);
        var response = infer(pool);
        assertEquals(503, response.getStatusCode().value());
        assertEquals(0, b.calls);
        assertEquals("NOT_RETRYABLE", ((ErrorResponse) response.getBody()).routing().terminalReason());
        assertTrue(pool.counts().values().stream().allMatch(n -> n == 0));
    }

    @Test void interruptedBeforeDispatchMakesNoAttemptAndPreservesFlag() {
        var a = new FakeWorker(8001, null); var pool = pool(a);
        Thread.currentThread().interrupt();
        try {
            var response = infer(pool);
            assertEquals(503, response.getStatusCode().value());
            var routing = ((ErrorResponse) response.getBody()).routing();
            assertEquals("INTERRUPTED", routing.terminalReason());
            assertEquals("INTERRUPTED", ((ErrorResponse) response.getBody()).error());
            assertTrue(routing.attempts().isEmpty());
            assertEquals(0, a.calls);
            assertEquals(0, pool.counts().get(a.endpoint()));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @Test void budgetExpiredAlternateDoesNotPenalizeHealthyWorker() {
        var http = new org.springframework.web.client.RestClientResponseException("worker",503,"",null,
                new byte[0], java.nio.charset.StandardCharsets.UTF_8);
        var a = new FakeWorker(8001,new WorkerClient.WorkerErrorException("retry",http));
        var b = new FakeWorker(8002,new BudgetExpiredException()); var pool = pool(a,b);
        var response = infer(pool);
        var routing = ((ErrorResponse) response.getBody()).routing();
        assertEquals("BUDGET_EXHAUSTED",routing.terminalReason());
        assertEquals("BUDGET_EXPIRED",routing.attempts().get(1).failureClass());
        assertTrue(pool.counts().values().stream().allMatch(n -> n == 0));
        var next = pool.reserveFailover(null,null,Set.of());
        try (var reservation = next.reservation()) {
            assertNotNull(reservation);
            var evidence = next.decision().candidates().stream().filter(c -> c.endpoint().equals(b.endpoint())).findFirst().orElseThrow();
            assertEquals(0,evidence.health().cooldownMs());
        }
    }

    @Test void configuredWorkerTimeoutStillSetsCooldown() {
        var a = new FakeWorker(8001,new WorkerClient.WorkerUnavailableException("read",new HttpTimeoutException("read")));
        var b = new FakeWorker(8002,null); var pool = pool(a,b);
        infer(pool);
        var next = pool.reserveFailover(null,null,Set.of());
        try (var reservation = next.reservation()) {
            assertNotNull(reservation);
            assertTrue(next.decision().candidates().stream().filter(c -> c.endpoint().equals(a.endpoint()))
                    .findFirst().orElseThrow().health().cooldownMs() > 0);
        }
    }

    @Test void interruptedDuringAttemptReleasesAndNeverRetries() {
        var a = new FakeWorker(8001, null) {
            @Override WorkerPrediction predict(String text, DispatchBudget budget) {
                calls++;
                Thread.currentThread().interrupt();
                throw new WorkerUnavailableException("interrupted", new InterruptedException());
            }
        };
        var b = new FakeWorker(8002, null); var pool = pool(a,b);
        try {
            var response = infer(pool);
            var routing = ((ErrorResponse) response.getBody()).routing();
            assertEquals(503, response.getStatusCode().value());
            assertEquals(1, routing.attempts().size());
            assertEquals("INTERRUPTED", routing.attempts().get(0).failureClass());
            assertEquals(0, b.calls);
            assertTrue(pool.counts().values().stream().allMatch(n -> n == 0));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }
    @Test void threeFailuresStillOnlyTwoAttempts() {
        var a = new FakeWorker(8001, refused()); var b = new FakeWorker(8002, refused()); var c = new FakeWorker(8003, refused());
        var pool = pool(a,b,c); var response = infer(pool);
        assertEquals(2, a.calls + b.calls + c.calls);
        assertEquals("ATTEMPTS_EXHAUSTED", ((ErrorResponse) response.getBody()).routing().terminalReason());
        assertEquals(2, ((ErrorResponse) response.getBody()).routing().attempts().size());
        assertTrue(pool.counts().values().stream().allMatch(n -> n == 0));
    }

    @Test void noAlternateKeepsOriginalFailureStatusAndOneAttempt() {
        var a = new FakeWorker(8001, refused()); var pool = pool(a);
        var response = infer(pool);
        assertEquals(503, response.getStatusCode().value());
        var routing = ((ErrorResponse) response.getBody()).routing();
        assertEquals("NO_ALTERNATE", routing.terminalReason());
        assertEquals(1, routing.attempts().size());
        assertEquals(a.endpoint(), routing.chosen());
        assertEquals(0, pool.counts().get(a.endpoint()));
    }

    @Test void disabledPathDoesNotRetryOrExposeAttemptFields() {
        var a = new FakeWorker(8001, refused()); var b = new FakeWorker(8002, null); var pool = pool(a,b);
        var response = new InferenceController(pool, null, tracker(), new FailoverSettings(false, 1))
                .infer(new InferenceRequest("TEXT", "hello", null, null));
        assertEquals(503, response.getStatusCode().value());
        assertEquals(1, a.calls); assertEquals(0, b.calls);
        assertNull(((ErrorResponse) response.getBody()).routing());
        assertTrue(pool.counts().values().stream().allMatch(n -> n == 0));
    }

    @Test void exactHttpAllowlistControlsControllerRetry() {
        for (int status : new int[] {400,404,422,500,502,503,504}) {
            var http = new org.springframework.web.client.RestClientResponseException("worker", status, "", null,
                    new byte[0], java.nio.charset.StandardCharsets.UTF_8);
            var a = new FakeWorker(8001, new WorkerClient.WorkerErrorException("http", http));
            var b = new FakeWorker(8002, null); var pool = pool(a,b);
            var response = infer(pool);
            boolean retry = status == 502 || status == 503;
            assertEquals(retry ? 200 : 502, response.getStatusCode().value(), "worker status " + status);
            assertEquals(retry ? 1 : 0, b.calls);
            assertTrue(pool.counts().values().stream().allMatch(n -> n == 0));
        }
    }
}
