package com.dhir.spring_boot.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

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
 * A worker whose /health claims one revision but whose /predict reports another. Version-aware
 * dispatch must not pass that answer off as the requested version: 502 WORKER_MODEL_MISMATCH,
 * and the worker's cached identity is dropped (it is asked again on the next request).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkerModelMismatchTest {

    static final String CLAIMED = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    static final String ACTUAL = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    static final FakeWorker LIAR = new FakeWorker("worker-x", "art", CLAIMED, ACTUAL);

    @DynamicPropertySource
    static void workers(DynamicPropertyRegistry registry) {
        registry.add("platform.worker.urls", LIAR::url);
    }

    @AfterAll
    static void stop() {
        LIAR.stop();
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void registry() {
        jdbc.sql("DELETE FROM model_versions").update();
        jdbc.sql("DELETE FROM models").update();
        jdbc.sql("INSERT INTO models (id, name, task, capability) VALUES ('mismatch-model', 'm', 't', 'TEXT')").update();
        jdbc.sql("INSERT INTO model_versions (model_id, version, artifact, artifact_revision) "
                + "VALUES ('mismatch-model', 'v1', 'art', :r)").param("r", CLAIMED).update();
    }

    HttpResponse<String> post(String json) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/inference"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void answerWithAnotherRevisionIsRejectedAndIdentityReProbed() throws Exception {
        HttpResponse<String> r = post("{\"input\":\"x\",\"model\":\"mismatch-model\",\"version\":\"v1\"}");
        assertEquals(502, r.statusCode(), r.body());
        assertTrue(r.body().contains("\"error\":\"WORKER_MODEL_MISMATCH\""), r.body());
        int probes = LIAR.healthHits.get();
        post("{\"input\":\"x\",\"model\":\"mismatch-model\",\"version\":\"v1\"}");
        assertTrue(LIAR.healthHits.get() > probes, "identity was asked again after the mismatch");
        // Requests that name no model are not checked against the registry (unchanged behaviour).
        assertEquals(200, post("{\"input\":\"x\"}").statusCode());
    }
}
