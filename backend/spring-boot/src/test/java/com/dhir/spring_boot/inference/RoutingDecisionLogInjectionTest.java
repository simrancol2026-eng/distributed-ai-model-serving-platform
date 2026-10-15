package com.dhir.spring_boot.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Codex review of 6469db4: a worker's /health identity is not validated, so a worker reporting a model
 * name with a newline must not split the one-line "routing decision" log entry. The fake worker below
 * reports "art<LF>FORGED routing decision ..." as its model.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class RoutingDecisionLogInjectionTest {

    static final String REV_1 = "1111111111111111111111111111111111111111";
    static final String REV_2 = "2222222222222222222222222222222222222222";

    // In the /health JSON "\n" (backslash + n) decodes to a real line feed in the reported model name.
    static final FakeWorker EVIL = new FakeWorker("worker-evil", "art\\nFORGED routing decision requestId=forged", REV_2);

    @DynamicPropertySource
    static void workers(DynamicPropertyRegistry registry) {
        registry.add("platform.worker.urls", EVIL::url);
        registry.add("platform.tracking.pause-after-failure-ms", () -> "0");
    }

    @AfterAll
    static void stop() {
        EVIL.stop();
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcClient jdbc;

    @Test
    void workerReportedNewlineStaysOnOneLogLine(CapturedOutput output) throws Exception {
        jdbc.sql("DELETE FROM model_versions").update();
        jdbc.sql("DELETE FROM models").update();
        jdbc.sql("INSERT INTO models (id, name, task, capability) VALUES ('sent', 'sent', 'sentiment-analysis', 'TEXT')")
                .update();
        jdbc.sql("INSERT INTO model_versions (model_id, version, artifact, artifact_revision) "
                + "VALUES ('sent', 'v1', 'art', '" + REV_1 + "')").update();

        HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/api/inference"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"input\":\"good\",\"model\":\"sent\",\"version\":\"v1\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(422, r.statusCode(), r.body()); // the evil worker serves another revision
        Matcher m = Pattern.compile("\"requestId\":\"([^\"]+)\"").matcher(r.body());
        assertTrue(m.find(), r.body());
        String id = m.group(1);

        List<String> lines = output.getAll().lines().filter(l -> l.contains("routing decision")).toList();
        List<String> ours = lines.stream().filter(l -> l.contains("requestId=" + id)).toList();
        assertEquals(1, ours.size(), String.join("\n", lines));
        assertTrue(ours.get(0).contains("SERVES_OTHER(art\\nFORGED routing decision requestId=forged@" + REV_2 + ")"),
                ours.get(0));
        // no log line STARTS with the forged text (it was never split off onto its own line)
        assertFalse(output.getAll().lines().anyMatch(l -> l.startsWith("FORGED")), output.getAll());

        // the stored JSON keeps the real value, JSON-escaped
        String stored = jdbc.sql("SELECT routing_decision FROM inference_requests WHERE request_id = :id")
                .param("id", id).query(String.class).single();
        assertTrue(stored.contains("\"served\":\"art\\nFORGED routing decision requestId=forged@" + REV_2 + "\""), stored);
    }
}
