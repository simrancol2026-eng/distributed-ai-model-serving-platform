package com.dhir.spring_boot.inference;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class BudgetedWorkerTransportTest {
    private int sends;
    private CompletableFuture<HttpResponse<String>> next;
    private final BudgetedWorkerTransport transport = new BudgetedWorkerTransport(request -> { sends++; return next; },
            URI.create("http://localhost:8011/predict"), 30000);

    private void respond(CompletableFuture<HttpResponse<String>> future) {
        next = future;
    }

    @Test void stalledBodyFutureIsCancelledAtBudget() {
        var future = new CompletableFuture<HttpResponse<String>>();
        respond(future);
        var failure = assertThrows(WorkerClient.WorkerUnavailableException.class,
                () -> transport.predict("x", new DispatchBudget(30)));
        assertEquals(FailoverFailure.BUDGET_EXPIRED, FailoverFailure.classify(failure));
        assertTrue(future.isCancelled());
    }

    @Test void spentBudgetNeverSends() {
        AtomicLong clock = new AtomicLong();
        DispatchBudget budget = new DispatchBudget(1, clock::get);
        clock.set(1_000_000L);
        assertThrows(WorkerClient.WorkerUnavailableException.class, () -> transport.predict("x", budget));
        assertEquals(0, sends);
    }

    @Test void configuredReadTimeoutStillCountsAsWorkerTimeout() {
        var future = new CompletableFuture<HttpResponse<String>>();
        var shortRead = new BudgetedWorkerTransport(request -> future,URI.create("http://localhost:8011/predict"),20);
        var failure = assertThrows(WorkerClient.WorkerUnavailableException.class,
                () -> shortRead.predict("x",new DispatchBudget(3000)));
        assertEquals(FailoverFailure.TIMEOUT,FailoverFailure.classify(failure));
        assertTrue(future.isCancelled());
    }

    @Test void connectTimeoutIsNotConfusedWithLongerBudgetCap() {
        respond(CompletableFuture.failedFuture(new HttpConnectTimeoutException("connect")));
        var failure = assertThrows(WorkerClient.WorkerUnavailableException.class,
                () -> transport.predict("x",new DispatchBudget(1000)));
        assertEquals(FailoverFailure.CONNECT_TIMEOUT,FailoverFailure.classify(failure));
    }

    @Test void requestTimerCappedByBudgetIsBudgetExpiry() {
        respond(CompletableFuture.failedFuture(new HttpTimeoutException("request timer")));
        var failure = assertThrows(WorkerClient.WorkerUnavailableException.class,
                () -> transport.predict("x",new DispatchBudget(1000)));
        assertEquals(FailoverFailure.BUDGET_EXPIRED,FailoverFailure.classify(failure));
    }

    @Test void interruptedCallerNeverSendsAndRetainsFlag() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(WorkerClient.WorkerUnavailableException.class,
                    () -> transport.predict("x", new DispatchBudget(1000)));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(0, sends);
        } finally { Thread.interrupted(); }
    }

    @Test void statusAndMalformedPayloadRemainDistinct() {
        respond(CompletableFuture.completedFuture(new Response(503, "")));
        var failure = assertThrows(WorkerClient.WorkerErrorException.class,
                () -> transport.predict("x", new DispatchBudget(1000)));
        assertEquals(FailoverFailure.TRANSIENT_HTTP, FailoverFailure.classify(failure));
        respond(CompletableFuture.completedFuture(new Response(200, "not json")));
        var malformed = assertThrows(WorkerClient.WorkerErrorException.class,
                () -> transport.predict("x", new DispatchBudget(1000)));
        assertFalse(FailoverFailure.classify(malformed).retryable());
    }

    private record Response(int statusCode, String body) implements HttpResponse<String> {
        public HttpRequest request() { return null; }
        public java.util.Optional<HttpResponse<String>> previousResponse() { return java.util.Optional.empty(); }
        public HttpHeaders headers() { return HttpHeaders.of(java.util.Map.of(), (a,b) -> true); }
        public java.util.Optional<javax.net.ssl.SSLSession> sslSession() { return java.util.Optional.empty(); }
        public URI uri() { return URI.create("http://localhost:8011/predict"); }
        public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }
}
