package com.dhir.spring_boot.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Persistent request lifecycle tracking (Sprint 2) against the real Spring app, in-memory H2, one
 * FAKE worker A that answers and one dead worker URL. Requests that name no model alternate A, dead,
 * A, dead, ... (round-robin), so each test sends until it gets the outcome it needs.
 *
 * H2 evidence only; the same SQL is exercised on real PostgreSQL by scripts/request_tracking_e2e_test.py.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RequestTrackingApiTest {

    static final String REV = "4444444444444444444444444444444444444444";
    static final FakeWorker A = new FakeWorker("worker-a", "art", REV);
    static final String DEAD = FakeWorker.deadUrl();

    @DynamicPropertySource
    static void workers(DynamicPropertyRegistry registry) {
        registry.add("platform.worker.urls", () -> A.url() + "," + DEAD);
        // No pause after the deliberate tracking failure below, so the other tests here are not affected.
        registry.add("platform.tracking.pause-after-failure-ms", () -> "0");
    }

    @AfterAll
    static void stop() {
        A.stop();
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcClient jdbc;

    final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void registry() {
        jdbc.sql("DELETE FROM model_versions").update();
        jdbc.sql("DELETE FROM models").update();
        jdbc.sql("INSERT INTO models (id, name, task, capability) VALUES ('track-model', 't', 'sentiment-analysis', 'TEXT')")
                .update();
        jdbc.sql("INSERT INTO model_versions (model_id, version, artifact, artifact_revision) "
                + "VALUES ('track-model', 'v1', 'art', :r)").param("r", REV).update();
    }

    // ---- helpers ----

    HttpResponse<String> post(String json) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base() + "/api/inference"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    String base() {
        return "http://127.0.0.1:" + port;
    }

    /** Sends the same no-model request (at most 4 times) until the wanted HTTP status comes back. */
    HttpResponse<String> postUntil(String json, int status) throws Exception {
        HttpResponse<String> r = null;
        for (int i = 0; i < 4; i++) {
            r = post(json);
            if (r.statusCode() == status) {
                return r;
            }
        }
        throw new AssertionError("never got " + status + "; last: " + r.statusCode() + " " + r.body());
    }

    HttpResponse<String> tracked(String requestId) throws Exception {
        HttpResponse<String> r = get("/api/requests/" + requestId);
        assertEquals(200, r.statusCode(), r.body());
        return r;
    }

    static String text(String body, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":\"([^\"]*)\"").matcher(body);
        return m.find() ? m.group(1) : null;
    }

    static Long number(String body, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":(-?\\d+)").matcher(body);
        return m.find() ? Long.parseLong(m.group(1)) : null;
    }

    static List<String> requestIds(String body) {
        List<String> ids = new ArrayList<>();
        Matcher m = Pattern.compile("\"requestId\":\"([^\"]*)\"").matcher(body);
        while (m.find()) {
            ids.add(m.group(1));
        }
        return ids;
    }

    // ---- lifecycle ----

    @Test
    void successfulRequestIsReceivedDispatchedAndSucceeded() throws Exception {
        String input = "lifecycle success text";
        HttpResponse<String> r = postUntil("{\"capability\":\"TEXT\",\"input\":\"" + input + "\"}", 200);
        String id = text(r.body(), "requestId");

        String t = tracked(id).body();
        assertEquals(id, text(t, "requestId"));
        assertEquals("SUCCEEDED", text(t, "status"));
        assertEquals(200L, number(t, "httpStatus"));
        assertNull(text(t, "errorCode"));
        assertEquals("TEXT", text(t, "capability"));
        assertEquals(A.endpoint(), text(t, "workerEndpoint"), "the endpoint the request was sent to");
        assertEquals("worker-a", text(t, "workerId"), "the id the worker reported");
        assertEquals((long) input.length(), number(t, "inputChars"));
        assertNotNull(text(t, "receivedAt"));
        assertNotNull(text(t, "dispatchedAt"));
        assertNotNull(text(t, "completedAt"));
        assertTrue(number(t, "totalMs") >= 0);
        assertNull(text(t, "requestedModel"));
        assertNull(text(t, "resolvedVersion"));
        assertFalse(t.contains(input), "the input text itself must not be stored or returned: " + t);
    }

    @Test
    void downWorkerIsDispatchedThenFailedWith503() throws Exception {
        HttpResponse<String> r = postUntil("{\"input\":\"x\"}", 503);
        String t = tracked(text(r.body(), "requestId")).body();
        assertEquals("FAILED", text(t, "status"));
        assertEquals(503L, number(t, "httpStatus"));
        assertEquals("WORKER_UNAVAILABLE", text(t, "errorCode"));
        assertEquals(DEAD.substring("http://".length()), text(t, "workerEndpoint"));
        assertNotNull(text(t, "dispatchedAt"), "it was sent to a worker before failing");
        assertNull(text(t, "workerId"), "no worker answered");
    }

    @Test
    void requestRejectedBeforeDispatchIsFailedWithoutWorker() throws Exception {
        HttpResponse<String> r = post("{\"input\":\"   \"}");
        assertEquals(400, r.statusCode(), r.body());
        String t = tracked(text(r.body(), "requestId")).body();
        assertEquals("FAILED", text(t, "status"));
        assertEquals(400L, number(t, "httpStatus"));
        assertEquals("INVALID_REQUEST", text(t, "errorCode"));
        assertNull(text(t, "dispatchedAt"));
        assertNull(text(t, "workerEndpoint"));
        assertNotNull(text(t, "completedAt"));

        HttpResponse<String> unknown = post("{\"input\":\"x\",\"model\":\"no-such-model\",\"version\":\"v7\"}");
        assertEquals(404, unknown.statusCode(), unknown.body());
        String u = tracked(text(unknown.body(), "requestId")).body();
        assertEquals("FAILED", text(u, "status"));
        assertEquals("MODEL_NOT_FOUND", text(u, "errorCode"));
        assertEquals("no-such-model", text(u, "requestedModel"));
        assertEquals("v7", text(u, "requestedVersion"));
        assertNull(text(u, "dispatchedAt"));
    }

    @Test
    void versionedRequestRecordsTheResolvedVersionAndRevision() throws Exception {
        // Only A reports art@REV (the dead worker has no identity), so this always goes to A.
        HttpResponse<String> r = post("{\"input\":\"x\",\"model\":\"track-model\"}");
        assertEquals(200, r.statusCode(), r.body());
        String t = tracked(text(r.body(), "requestId")).body();
        assertEquals("SUCCEEDED", text(t, "status"));
        assertEquals("track-model", text(t, "requestedModel"));
        assertNull(text(t, "requestedVersion"), "the client did not name a version");
        assertEquals("v1", text(t, "resolvedVersion"), "'latest' resolved to v1");
        assertEquals("art", text(t, "artifact"));
        assertEquals(REV, text(t, "artifactRevision"));
        assertEquals(A.endpoint(), text(t, "workerEndpoint"));
    }

    // ---- read API ----

    @Test
    void listIsNewestFirstAndLimitIsValidated() throws Exception {
        List<String> sent = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            HttpResponse<String> r = post("{\"input\":\"\"}");
            sent.add(text(r.body(), "requestId"));
        }
        HttpResponse<String> list = get("/api/requests?limit=3");
        assertEquals(200, list.statusCode(), list.body());
        assertEquals(List.of(sent.get(2), sent.get(1), sent.get(0)), requestIds(list.body()));

        HttpResponse<String> deflt = get("/api/requests");
        assertEquals(200, deflt.statusCode(), deflt.body());
        assertTrue(requestIds(deflt.body()).size() <= 20);

        for (String bad : new String[] { "0", "101", "abc", "-1" }) {
            HttpResponse<String> r = get("/api/requests?limit=" + bad);
            assertEquals(400, r.statusCode(), bad + " -> " + r.body());
            assertTrue(r.body().contains("\"error\":\"INVALID_REQUEST\""), r.body());
        }
    }

    @Test
    void unknownIdIs404AndMalformedIdIs400() throws Exception {
        HttpResponse<String> missing = get("/api/requests/" + UUID.randomUUID());
        assertEquals(404, missing.statusCode(), missing.body());
        assertTrue(missing.body().contains("\"error\":\"REQUEST_NOT_FOUND\""), missing.body());

        HttpResponse<String> malformed = get("/api/requests/not-a-uuid");
        assertEquals(400, malformed.statusCode(), malformed.body());
        assertTrue(malformed.body().contains("\"error\":\"INVALID_REQUEST\""), malformed.body());
    }

    // ---- concurrency and failure of the tracking store ----

    @Test
    void concurrentRequestsEachEndInExactlyOneFinalRow() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            List<CompletableFuture<HttpResponse<String>>> futures = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        return post("{\"input\":\"concurrent\"}");
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }, pool));
            }
            Set<String> ids = new HashSet<>();
            for (CompletableFuture<HttpResponse<String>> f : futures) {
                HttpResponse<String> r = f.get();
                String id = text(r.body(), "requestId");
                assertTrue(ids.add(id), "requestIds are unique");
                String t = tracked(id).body();
                String expected = r.statusCode() == 200 ? "SUCCEEDED" : "FAILED";
                assertEquals(expected, text(t, "status"), t);
                assertEquals((long) r.statusCode(), number(t, "httpStatus"));
            }
            assertEquals(20, ids.size());
            long rows = jdbc.sql("SELECT COUNT(*) FROM inference_requests WHERE request_id IN (:ids)")
                    .param("ids", List.copyOf(ids)).query(Long.class).single();
            assertEquals(20L, rows, "one row per request");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void inferenceStillWorksWhenTheTrackingTableIsUnavailable() throws Exception {
        jdbc.sql("ALTER TABLE inference_requests RENAME TO inference_requests_off").update();
        String id;
        try {
            HttpResponse<String> r = postUntil("{\"input\":\"x\"}", 200);
            id = text(r.body(), "requestId");
            HttpResponse<String> read = get("/api/requests/" + id);
            assertEquals(503, read.statusCode(), read.body());
            assertTrue(read.body().contains("\"error\":\"TRACKING_UNAVAILABLE\""), read.body());
            HttpResponse<String> list = get("/api/requests");
            assertEquals(503, list.statusCode(), list.body());
        } finally {
            jdbc.sql("ALTER TABLE inference_requests_off RENAME TO inference_requests").update();
        }
        // The request was served but could not be recorded; that is the documented best-effort trade-off.
        assertEquals(404, get("/api/requests/" + id).statusCode());
    }
}
