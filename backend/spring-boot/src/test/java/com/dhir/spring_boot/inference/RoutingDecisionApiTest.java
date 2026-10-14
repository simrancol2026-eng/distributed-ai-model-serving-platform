package com.dhir.spring_boot.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
 * Detailed routing decisions (Sprint 2 #5) against the real Spring app, in-memory H2 and fake workers:
 *   A serves art@REV_1, B serves art@REV_2, D is a URL where nothing listens (identity unknown).
 * Checks the decision returned in the response and the one stored with the tracked request.
 * H2 evidence only; real PostgreSQL + real workers are checked by scripts/routing_decision_e2e_test.py.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RoutingDecisionApiTest {

    static final String REV_1 = "1111111111111111111111111111111111111111";
    static final String REV_2 = "2222222222222222222222222222222222222222";
    static final String REV_3 = "3333333333333333333333333333333333333333";

    static final FakeWorker A = new FakeWorker("worker-a", "art", REV_1);
    static final FakeWorker B = new FakeWorker("worker-b", "art", REV_2);
    static final String D_URL = FakeWorker.deadUrl();

    @DynamicPropertySource
    static void workers(DynamicPropertyRegistry registry) {
        registry.add("platform.worker.urls", () -> A.url() + "," + B.url() + "," + D_URL);
        registry.add("platform.tracking.pause-after-failure-ms", () -> "0");
    }

    @AfterAll
    static void stop() {
        A.stop();
        B.stop();
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcClient jdbc;

    final HttpClient http = HttpClient.newHttpClient();

    static String endpoint(FakeWorker w) {
        return w.endpoint();
    }

    @BeforeEach
    void registry() {
        jdbc.sql("DELETE FROM model_versions").update();
        jdbc.sql("DELETE FROM models").update();
        jdbc.sql("INSERT INTO models (id, name, task, capability) VALUES ('sent', 'sent', 'sentiment-analysis', 'TEXT')")
                .update();
        version("v1", REV_1);
        version("v3", REV_3); // no worker serves REV_3
    }

    void version(String label, String revision) {
        jdbc.sql("INSERT INTO model_versions (model_id, version, artifact, artifact_revision) "
                + "VALUES ('sent', :v, 'art', :r)").param("v", label).param("r", revision).update();
    }

    HttpResponse<String> post(String json) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/inference"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
    }

    String stored(String requestId) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/api/requests/" + requestId)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        return r.body();
    }

    String storedJson(String requestId) {
        return jdbc.sql("SELECT routing_decision FROM inference_requests WHERE request_id = :id")
                .param("id", requestId).query(String.class).single();
    }

    static String requestId(String body) {
        Matcher m = Pattern.compile("\"requestId\":\"([^\"]+)\"").matcher(body);
        assertTrue(m.find(), body);
        return m.group(1);
    }

    @Test
    void versionedRequestExplainsEveryWorker() throws Exception {
        HttpResponse<String> r = post("{\"input\":\"good\",\"model\":\"sent\",\"version\":\"v1\"}");
        assertEquals(200, r.statusCode(), r.body());
        String id = requestId(r.body());
        String a = "{\"endpoint\":\"" + endpoint(A) + "\",\"eligible\":true,\"note\":\"ELIGIBLE\",\"served\":\"art@" + REV_1 + "\"}";
        String b = "{\"endpoint\":\"" + endpoint(B) + "\",\"eligible\":false,\"note\":\"SERVES_OTHER\",\"served\":\"art@" + REV_2 + "\"}";
        String d = "{\"endpoint\":\"" + D_URL.replaceFirst("^http://", "") + "\",\"eligible\":false,\"note\":\"IDENTITY_UNKNOWN\",\"served\":null}";
        String expectedCandidates = "\"candidates\":[" + a + "," + b + "," + d + "]";

        String json = storedJson(id);
        assertTrue(json.startsWith("{\"strategy\":\"VERSION_AWARE\",\"target\":\"art@" + REV_1
                + "\",\"poolSize\":3,\"eligibleCount\":1,"), json);
        assertTrue(json.contains(expectedCandidates), json);
        assertTrue(json.contains("\"chosen\":\"" + endpoint(A) + "\""), json);

        // GET /api/requests/{id} returns the same decision as a JSON object
        assertTrue(stored(id).contains("\"routing\":" + json), stored(id));
        // and the inference response carries it too
        assertTrue(r.body().contains("\"strategy\":\"VERSION_AWARE\""), r.body());
        assertTrue(r.body().contains("\"chosen\":\"" + endpoint(A) + "\""), r.body());
        assertTrue(r.body().contains("\"note\":\"IDENTITY_UNKNOWN\""), r.body());
    }

    @Test
    void unversionedRequestsRecordTheRoundRobinChoiceTheyWereSentTo() throws Exception {
        for (int i = 0; i < 3; i++) {
            HttpResponse<String> r = post("{\"input\":\"good\"}");
            String id = requestId(r.body());
            String json = storedJson(id);
            assertTrue(json.startsWith("{\"strategy\":\"ROUND_ROBIN_ALL\",\"target\":null,\"poolSize\":3,\"eligibleCount\":3,"),
                    json);
            String endpoint = jdbc.sql("SELECT worker_endpoint FROM inference_requests WHERE request_id = :id")
                    .param("id", id).query(String.class).single();
            assertNotNull(endpoint);
            assertTrue(json.contains("\"chosen\":\"" + endpoint + "\""), json + " vs " + endpoint);
        }
    }

    @Test
    void rowsWithoutADecisionReturnRoutingNull() throws Exception {
        // like a row written before V3 (or whose routing write was skipped)
        String id = "aaaaaaaa-0000-4000-8000-000000000001";
        jdbc.sql("DELETE FROM inference_requests WHERE request_id = :id").param("id", id).update();
        jdbc.sql("INSERT INTO inference_requests (request_id, status) VALUES (:id, 'RECEIVED')").param("id", id).update();
        assertTrue(stored(id).contains("\"routing\":null"), stored(id));
    }

    @Test
    void rejectedRequestIsStoredAsNotRouted() throws Exception {
        HttpResponse<String> r = post("{\"input\":\"  \"}");
        assertEquals(400, r.statusCode(), r.body());
        assertEquals("{\"strategy\":\"NOT_ROUTED\",\"target\":null,\"poolSize\":3,\"eligibleCount\":0,"
                + "\"candidates\":[],\"chosen\":null,\"reason\":\"INVALID_REQUEST\"}", storedJson(requestId(r.body())));
    }

    @Test
    void noEligibleWorkerListsWhyEachWorkerWasSkipped() throws Exception {
        HttpResponse<String> r = post("{\"input\":\"good\",\"model\":\"sent\",\"version\":\"v3\"}");
        assertEquals(422, r.statusCode(), r.body());
        String json = storedJson(requestId(r.body()));
        assertTrue(json.startsWith("{\"strategy\":\"VERSION_AWARE\",\"target\":\"art@" + REV_3
                + "\",\"poolSize\":3,\"eligibleCount\":0,"), json);
        assertTrue(json.contains("\"note\":\"SERVES_OTHER\",\"served\":\"art@" + REV_1 + "\""), json);
        assertTrue(json.contains("\"note\":\"IDENTITY_UNKNOWN\""), json);
        assertTrue(json.contains("\"chosen\":null"), json);
        assertTrue(json.contains("NO_ELIGIBLE_WORKER"), json);
    }
}
