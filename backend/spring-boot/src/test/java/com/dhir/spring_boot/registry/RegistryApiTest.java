package com.dhir.spring_boot.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Registry API tests against the real Spring Boot app on a random port.
 *
 * STORAGE: these tests use in-memory H2 in PostgreSQL mode (src/test/resources/config/application.properties).
 * They test the API, validation and the constraint-to-409 translation. They are NOT evidence that the
 * registry works on a real PostgreSQL server or that data survives a restart; that is checked separately
 * with scripts/registry_persistence_check.py against PostgreSQL.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RegistryApiTest {

    static final String REV_A = "714eb0fa89d2f80546fda750413ed43d93601a13";
    static final String REV_B = "0123456789abcdef0123456789abcdef01234567";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcClient jdbc;

    final HttpClient http = HttpClient.newHttpClient();

    /** Test-only reset of the in-memory H2 database. The PostgreSQL scripts never delete data. */
    @BeforeEach
    void emptyRegistry() {
        jdbc.sql("DELETE FROM model_versions").update();
        jdbc.sql("DELETE FROM models").update();
    }

    HttpRequest.Builder req(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
    }

    HttpResponse<String> get(String path) throws Exception {
        return http.send(req(path).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpRequest postRequest(String path, String json) {
        return req(path).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build();
    }

    HttpResponse<String> post(String path, String json) throws Exception {
        return http.send(postRequest(path, json), HttpResponse.BodyHandlers.ofString());
    }

    static String model(String id) {
        return """
                {"id":"%s","name":"DistilBERT SST-2","task":"sentiment-analysis","capability":"TEXT"}"""
                .formatted(id);
    }

    static String version(String label, String revision) {
        return """
                {"version":"%s","artifact":"distilbert-base-uncased-finetuned-sst-2-english",
                 "artifactRevision":"%s","framework":"transformers"}""".formatted(label, revision);
    }

    void assertError(HttpResponse<String> r, int status, String code) {
        assertEquals(status, r.statusCode(), r.body());
        assertTrue(r.body().contains("\"status\":\"error\""), r.body());
        assertTrue(r.body().contains("\"error\":\"" + code + "\""), r.body());
        assertTrue(r.body().contains("\"requestId\""), r.body());
    }

    // ---- happy path ----

    @Test
    void emptyRegistryListsAsEmptyArray() throws Exception {
        HttpResponse<String> r = get("/api/models");
        assertEquals(200, r.statusCode());
        assertEquals("[]", r.body());
    }

    @Test
    void createListAndGetModel() throws Exception {
        HttpResponse<String> created = post("/api/models", model("sentiment-distilbert"));
        assertEquals(201, created.statusCode(), created.body());
        assertEquals("/api/models/sentiment-distilbert", created.headers().firstValue("Location").orElse(null));
        assertTrue(created.body().contains("\"capability\":\"TEXT\""), created.body());
        assertTrue(created.body().contains("\"versionCount\":0"), created.body());
        assertTrue(created.body().contains("\"latestVersion\":null"), created.body());

        HttpResponse<String> list = get("/api/models");
        assertEquals(200, list.statusCode());
        assertTrue(list.body().contains("\"id\":\"sentiment-distilbert\""), list.body());
        // Frontend Model fields: no version yet, workers unknown, status = registered (not "running").
        assertTrue(list.body().contains("\"version\":null"), list.body());
        assertTrue(list.body().contains("\"workers\":null"), list.body());
        assertTrue(list.body().contains("\"status\":\"REGISTERED\""), list.body());

        HttpResponse<String> one = get("/api/models/sentiment-distilbert");
        assertEquals(200, one.statusCode());
        assertTrue(one.body().contains("\"name\":\"DistilBERT SST-2\""), one.body());
    }

    @Test
    void stringsAreTrimmedAndCapabilityUppercased() throws Exception {
        HttpResponse<String> r = post("/api/models", """
                {"id":"  trimmed-model ","name":"  Name  ","task":" t ","capability":" text "}""");
        assertEquals(201, r.statusCode(), r.body());
        assertTrue(r.body().contains("\"id\":\"trimmed-model\""), r.body());
        assertTrue(r.body().contains("\"name\":\"Name\""), r.body());
        assertTrue(r.body().contains("\"capability\":\"TEXT\""), r.body());
    }

    @Test
    void versionsBelongToTheirModelAndLatestIsMostRecentlyRegistered() throws Exception {
        post("/api/models", model("m-one"));
        post("/api/models", model("m-two"));

        HttpResponse<String> v1 = post("/api/models/m-one/versions", version("v1", REV_A));
        assertEquals(201, v1.statusCode(), v1.body());
        assertEquals("/api/models/m-one/versions/v1", v1.headers().firstValue("Location").orElse(null));
        assertTrue(v1.body().contains("\"modelId\":\"m-one\""), v1.body());
        assertTrue(v1.body().contains("\"artifactRevision\":\"" + REV_A + "\""), v1.body());

        // Label "v0.9" sorts before "v1" as text, but it is registered later, so it is "latest".
        assertEquals(201, post("/api/models/m-one/versions", version("v0.9", REV_B)).statusCode());

        HttpResponse<String> list = get("/api/models/m-one/versions");
        assertEquals(200, list.statusCode());
        assertTrue(list.body().indexOf("\"version\":\"v1\"") < list.body().indexOf("\"version\":\"v0.9\""),
                "versions are listed oldest registration first: " + list.body());

        HttpResponse<String> detail = get("/api/models/m-one");
        assertTrue(detail.body().contains("\"versionCount\":2"), detail.body());
        assertTrue(detail.body().contains("\"latestVersion\":{"), detail.body());
        assertTrue(detail.body().replaceAll("\\s", "").matches(".*\"latestVersion\":\\{[^}]*\"version\":\"v0.9\".*"),
                detail.body());

        HttpResponse<String> models = get("/api/models");
        assertTrue(models.body().contains("\"version\":\"v0.9\""), models.body());

        // The other model has no versions: association is per model.
        assertEquals("[]", get("/api/models/m-two/versions").body());
        assertError(get("/api/models/m-two/versions/v1"), 404, "VERSION_NOT_FOUND");

        // The same label may be used by a different model.
        assertEquals(201, post("/api/models/m-two/versions", version("v1", REV_A)).statusCode());

        HttpResponse<String> got = get("/api/models/m-one/versions/v1");
        assertEquals(200, got.statusCode());
        assertTrue(got.body().contains("\"framework\":\"transformers\""), got.body());
    }

    @Test
    void uppercaseRevisionIsNormalizedToLowercase() throws Exception {
        post("/api/models", model("m-case"));
        HttpResponse<String> r = post("/api/models/m-case/versions", version("v1", REV_A.toUpperCase()));
        assertEquals(201, r.statusCode(), r.body());
        assertTrue(r.body().contains("\"artifactRevision\":\"" + REV_A + "\""), r.body());
    }

    // ---- invalid input: 400 ----

    @Test
    void invalidModelsAreRejectedWith400() throws Exception {
        assertError(post("/api/models", ""), 400, "INVALID_REQUEST");
        assertError(post("/api/models", "not json"), 400, "INVALID_REQUEST");
        assertError(post("/api/models", "[1,2]"), 400, "INVALID_REQUEST");
        assertError(post("/api/models", "{}"), 400, "INVALID_REQUEST");
        assertError(post("/api/models", model("Upper-Case")), 400, "INVALID_REQUEST");
        assertError(post("/api/models", model("a")), 400, "INVALID_REQUEST");
        assertError(post("/api/models", model("-leading-dash")), 400, "INVALID_REQUEST");
        assertError(post("/api/models", model("has/slash")), 400, "INVALID_REQUEST");
        assertError(post("/api/models", model("x".repeat(64))), 400, "INVALID_REQUEST");
        assertError(post("/api/models", """
                {"id":"ok-id","name":"   ","task":"t","capability":"TEXT"}"""), 400, "INVALID_REQUEST");
        assertError(post("/api/models", """
                {"id":"ok-id","name":"n","task":"t","capability":"AUDIO"}"""), 400, "INVALID_REQUEST");
        assertError(post("/api/models", """
                {"id":"ok-id","name":"%s","task":"t","capability":"TEXT"}""".formatted("n".repeat(201))),
                400, "INVALID_REQUEST");
        // Nothing was stored by the rejected requests.
        assertEquals("[]", get("/api/models").body());
    }

    @Test
    void invalidVersionsAreRejectedWith400() throws Exception {
        post("/api/models", model("m-val"));
        String path = "/api/models/m-val/versions";
        assertError(post(path, "{}"), 400, "INVALID_REQUEST");
        assertError(post(path, version("v1", "714eb0f")), 400, "INVALID_REQUEST"); // abbreviated hash
        assertError(post(path, version("v1", REV_A + "0")), 400, "INVALID_REQUEST"); // 41 chars
        assertError(post(path, version("v1", "g".repeat(40))), 400, "INVALID_REQUEST"); // not hex
        assertError(post(path, version("v1/2", REV_A)), 400, "INVALID_REQUEST"); // not path-safe
        assertError(post(path, version(".hidden", REV_A)), 400, "INVALID_REQUEST");
        assertError(post(path, version("latest", REV_A)), 400, "INVALID_REQUEST"); // reserved alias
        assertError(post(path, version("LATEST", REV_A)), 400, "INVALID_REQUEST");
        assertError(post(path, version("v".repeat(65), REV_A)), 400, "INVALID_REQUEST");
        assertError(post(path, """
                {"version":"v1","artifact":"bad artifact","artifactRevision":"%s"}""".formatted(REV_A)),
                400, "INVALID_REQUEST");
        assertEquals("[]", get(path).body());
    }

    // ---- unknown: 404 ----

    @Test
    void unknownModelOrVersionIs404() throws Exception {
        assertError(get("/api/models/nope"), 404, "MODEL_NOT_FOUND");
        assertError(get("/api/models/nope/versions"), 404, "MODEL_NOT_FOUND");
        assertError(get("/api/models/nope/versions/v1"), 404, "MODEL_NOT_FOUND");
        assertError(post("/api/models/nope/versions", version("v1", REV_A)), 404, "MODEL_NOT_FOUND");
        post("/api/models", model("m-known"));
        assertError(get("/api/models/m-known/versions/v9"), 404, "VERSION_NOT_FOUND");
    }

    // ---- duplicates: 409 ----

    @Test
    void duplicatesAreRejectedWith409() throws Exception {
        assertEquals(201, post("/api/models", model("m-dup")).statusCode());
        assertError(post("/api/models", model("m-dup")), 409, "MODEL_ALREADY_EXISTS");
        // Trimming applies before the uniqueness check.
        assertError(post("/api/models", model(" m-dup ")), 409, "MODEL_ALREADY_EXISTS");

        assertEquals(201, post("/api/models/m-dup/versions", version("v1", REV_A)).statusCode());
        assertError(post("/api/models/m-dup/versions", version("v1", REV_B)), 409, "VERSION_ALREADY_EXISTS");
        // Same artifact + revision under a new label is an alias, not a new version.
        assertError(post("/api/models/m-dup/versions", version("v2", REV_A)), 409, "DUPLICATE_ARTIFACT");
        assertError(post("/api/models/m-dup/versions", version("v2", REV_A.toUpperCase())), 409,
                "DUPLICATE_ARTIFACT");

        assertTrue(get("/api/models/m-dup").body().contains("\"versionCount\":1"));
    }

    @Test
    void concurrentDuplicateModelCreatesExactlyOne() throws Exception {
        List<Integer> statuses = concurrentPosts("/api/models", model("m-race"), 8);
        assertEquals(1, statuses.stream().filter(s -> s == 201).count(), statuses.toString());
        assertEquals(7, statuses.stream().filter(s -> s == 409).count(), statuses.toString());
        assertEquals(1L, jdbc.sql("SELECT COUNT(*) FROM models WHERE id = 'm-race'").query(Long.class).single());
    }

    @Test
    void concurrentDuplicateVersionCreatesExactlyOne() throws Exception {
        post("/api/models", model("m-vrace"));
        List<Integer> statuses = concurrentPosts("/api/models/m-vrace/versions", version("v1", REV_A), 8);
        assertEquals(1, statuses.stream().filter(s -> s == 201).count(), statuses.toString());
        assertEquals(7, statuses.stream().filter(s -> s == 409).count(), statuses.toString());
        assertEquals(1L, jdbc.sql("SELECT COUNT(*) FROM model_versions WHERE model_id = 'm-vrace'")
                .query(Long.class).single());
    }

    /** Sends n identical POSTs at (as nearly as possible) the same moment; returns their statuses. */
    List<Integer> concurrentPosts(String path, String json, int n) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(n); // one thread per request, so all wait on the latch
        List<CompletableFuture<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    start.await();
                    return http.send(postRequest(path, json), HttpResponse.BodyHandlers.discarding()).statusCode();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }, pool));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (CompletableFuture<Integer> f : futures) {
            statuses.add(f.get());
        }
        pool.shutdown();
        return statuses;
    }

    // ---- database constraints (direct SQL, H2) ----

    @Test
    void foreignKeyRejectsVersionForMissingModel() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                INSERT INTO model_versions (model_id, version, artifact, artifact_revision)
                VALUES ('ghost', 'v1', 'a', :rev)""").param("rev", REV_A).update());
    }

    @Test
    void uniqueConstraintsExistInTheSchemaNotOnlyInJava() throws Exception {
        post("/api/models", model("m-sql"));
        jdbc.sql("""
                INSERT INTO model_versions (model_id, version, artifact, artifact_revision)
                VALUES ('m-sql', 'v1', 'a', :rev)""").param("rev", REV_A).update();
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                INSERT INTO model_versions (model_id, version, artifact, artifact_revision)
                VALUES ('m-sql', 'v1', 'b', :rev)""").param("rev", REV_B).update());
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                INSERT INTO model_versions (model_id, version, artifact, artifact_revision)
                VALUES ('m-sql', 'v2', 'a', :rev)""").param("rev", REV_A).update());
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                INSERT INTO models (id, name, task, capability) VALUES ('m-cap', 'n', 't', 'AUDIO')""").update());
    }

    // ---- inference contract untouched ----

    @Test
    void healthIsStillPlainText() throws Exception {
        HttpResponse<String> r = get("/api/health");
        assertEquals(200, r.statusCode());
        assertEquals("Backend is healthy", r.body());
        assertFalse(r.headers().firstValue("Content-Type").orElse("").contains("json"));
    }
}
