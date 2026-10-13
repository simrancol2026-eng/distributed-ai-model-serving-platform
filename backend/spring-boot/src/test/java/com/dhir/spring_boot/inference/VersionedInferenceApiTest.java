package com.dhir.spring_boot.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
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
 * Version-aware dispatch (Sprint 2) against the real Spring app, the registry on in-memory H2,
 * and three FAKE worker processes (JDK HTTP servers) that report their own model + revision:
 *   A and B serve "art" at REV_1, C serves "art" at REV_2.
 * Registry: model "sent" (TEXT) has v1 -> REV_1 and v2 -> REV_2 (v2 registered later = latest).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class VersionedInferenceApiTest {

    static final String REV_1 = "1111111111111111111111111111111111111111";
    static final String REV_2 = "2222222222222222222222222222222222222222";
    static final String REV_3 = "3333333333333333333333333333333333333333";

    static final FakeWorker A = new FakeWorker("worker-a", "art", REV_1);
    static final FakeWorker B = new FakeWorker("worker-b", "art", REV_1);
    static final FakeWorker C = new FakeWorker("worker-c", "art", REV_2);

    @DynamicPropertySource
    static void workers(DynamicPropertyRegistry registry) {
        registry.add("platform.worker.urls", () -> A.url() + "," + B.url() + "," + C.url());
    }

    @AfterAll
    static void stop() {
        A.stop();
        B.stop();
        C.stop();
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
        model("sent", "TEXT");
        version("sent", "v1", REV_1);
        version("sent", "v2", REV_2); // registered after v1, so it is "latest"
        model("img", "IMAGE");
        version("img", "v1", REV_1);
        model("orphan", "TEXT");
        version("orphan", "v1", REV_3); // no worker serves REV_3
        model("empty", "TEXT"); // no versions
    }

    void model(String id, String capability) {
        jdbc.sql("INSERT INTO models (id, name, task, capability) VALUES (:id, :id, 'sentiment-analysis', :c)")
                .param("id", id).param("c", capability).update();
    }

    void version(String model, String label, String revision) {
        jdbc.sql("INSERT INTO model_versions (model_id, version, artifact, artifact_revision) "
                + "VALUES (:m, :v, 'art', :r)").param("m", model).param("v", label).param("r", revision).update();
    }

    HttpRequest request(String json) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/inference"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build();
    }

    HttpResponse<String> post(String json) throws Exception {
        return http.send(request(json), HttpResponse.BodyHandlers.ofString());
    }

    static String field(String body, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":\"([^\"]*)\"").matcher(body);
        return m.find() ? m.group(1) : null;
    }

    void assertError(HttpResponse<String> r, int status, String code) {
        assertEquals(status, r.statusCode(), r.body());
        assertTrue(r.body().contains("\"error\":\"" + code + "\""), r.body());
    }

    int[] hits() {
        return new int[] { A.hits.get(), B.hits.get(), C.hits.get() };
    }

    // ---- resolution and routing ----

    @Test
    void explicitVersionGoesOnlyToWorkersServingItsRevisionInTurn() throws Exception {
        int[] before = hits();
        List<String> served = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            HttpResponse<String> r = post("{\"capability\":\"TEXT\",\"input\":\"x\",\"model\":\"sent\",\"version\":\"v1\"}");
            assertEquals(200, r.statusCode(), r.body());
            assertEquals("sent", field(r.body(), "modelId"));
            assertEquals("v1", field(r.body(), "modelVersion"));
            assertEquals(REV_1, field(r.body(), "version"), "revision reported by the worker that served it");
            served.add(field(r.body(), "worker"));
        }
        for (int i = 1; i < served.size(); i++) {
            assertTrue(!served.get(i).equals(served.get(i - 1)), "alternates between A and B: " + served);
        }
        int[] after = hits();
        assertEquals(3, after[0] - before[0]);
        assertEquals(3, after[1] - before[1]);
        assertEquals(0, after[2] - before[2], "worker C (REV_2) must not be used for v1");
    }

    @Test
    void latestIsTheMostRecentlyRegisteredVersion() throws Exception {
        for (String json : new String[] { "{\"input\":\"x\",\"model\":\"sent\"}",
                "{\"input\":\"x\",\"model\":\"sent\",\"version\":\"latest\"}",
                "{\"input\":\"x\",\"model\":\"sent\",\"version\":\"LATEST\"}" }) {
            HttpResponse<String> r = post(json);
            assertEquals(200, r.statusCode(), r.body());
            assertEquals("v2", field(r.body(), "modelVersion"), json);
            assertEquals("worker-c", field(r.body(), "worker"), json);
            assertEquals(REV_2, field(r.body(), "version"), json);
        }
    }

    @Test
    void requestsWithoutModelBehaveAsBeforeAcrossAllWorkers() throws Exception {
        int[] before = hits();
        for (int i = 0; i < 3; i++) {
            HttpResponse<String> r = post("{\"capability\":\"TEXT\",\"input\":\"x\"}");
            assertEquals(200, r.statusCode(), r.body());
            assertTrue(r.body().contains("\"modelId\":null"), r.body());
            assertTrue(r.body().contains("\"modelVersion\":null"), r.body());
        }
        int[] after = hits();
        assertEquals(1, after[0] - before[0]);
        assertEquals(1, after[1] - before[1]);
        assertEquals(1, after[2] - before[2]);
    }

    // ---- errors ----

    @Test
    void unknownOrMalformedModelAndVersionAreRejected() throws Exception {
        int[] before = hits();
        assertError(post("{\"input\":\"x\",\"model\":\"nope\"}"), 404, "MODEL_NOT_FOUND");
        assertError(post("{\"input\":\"x\",\"model\":\"sent\",\"version\":\"v9\"}"), 404, "VERSION_NOT_FOUND");
        assertError(post("{\"input\":\"x\",\"model\":\"empty\"}"), 404, "VERSION_NOT_FOUND");
        assertError(post("{\"input\":\"x\",\"version\":\"v1\"}"), 400, "INVALID_REQUEST");
        assertError(post("{\"input\":\"x\",\"model\":\"Bad Id\"}"), 400, "INVALID_REQUEST");
        assertError(post("{\"input\":\"x\",\"model\":\"sent\",\"version\":\"v/1\"}"), 400, "INVALID_REQUEST");
        assertError(post("{\"input\":\"  \",\"model\":\"sent\"}"), 400, "INVALID_REQUEST");
        int[] after = hits();
        assertEquals(before[0] + before[1] + before[2], after[0] + after[1] + after[2], "no worker was called");
    }

    @Test
    void capabilityMismatchAndUnservedCapability() throws Exception {
        assertError(post("{\"capability\":\"IMAGE\",\"input\":\"x\",\"model\":\"sent\"}"), 422, "CAPABILITY_MISMATCH");
        // The model is IMAGE but every worker serves TEXT.
        assertError(post("{\"capability\":\"AUTO\",\"input\":\"x\",\"model\":\"img\"}"), 422, "NO_ELIGIBLE_WORKER");
    }

    @Test
    void versionThatNoWorkerServesFailsClosedInsteadOfSubstituting() throws Exception {
        int[] before = hits();
        HttpResponse<String> r = post("{\"input\":\"x\",\"model\":\"orphan\",\"version\":\"v1\"}");
        assertError(r, 422, "NO_ELIGIBLE_WORKER");
        assertTrue(r.body().contains(REV_3), "names the revision that is missing: " + r.body());
        int[] after = hits();
        assertEquals(before[0] + before[1] + before[2], after[0] + after[1] + after[2], "no worker was called");
    }

    // ---- concurrency ----

    @Test
    void concurrentVersionedRequestsSplitEvenlyAmongEligibleWorkersOnly() throws Exception {
        int[] before = hits();
        int n = 40;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    start.await();
                    return http.send(request("{\"input\":\"x\",\"model\":\"sent\",\"version\":\"v1\"}"),
                            HttpResponse.BodyHandlers.discarding()).statusCode();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }, pool));
        }
        start.countDown();
        for (CompletableFuture<Integer> f : futures) {
            assertEquals(200, f.get().intValue());
        }
        pool.shutdown();
        int[] after = hits();
        assertEquals(n / 2, after[0] - before[0], "worker A share");
        assertEquals(n / 2, after[1] - before[1], "worker B share");
        assertEquals(0, after[2] - before[2], "worker C serves another revision");
    }
}
