package com.dhir.spring_boot.inference;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.sun.net.httpserver.HttpServer;

/**
 * Full Spring app with a FAKE TEXT worker and a FAKE CODE worker (JDK HTTP servers), configured through
 * platform.worker.url and platform.worker.code.urls, plus a FAKE IMAGE worker that rejects every image with the
 * real worker's 422 invalid_image body. Proves bean wiring, JSON shape, GET /api/capabilities and the
 * 422 INVALID_IMAGE translation through Spring. It does NOT run any model.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SpecialistApiTest {

    static final HttpServer textWorker = fake("TEXT", "fake-sentiment", """
            {"worker":"text-a","model":"fake-sentiment","version":"rev1","task":"sentiment-analysis",
             "label":"POSITIVE","score":0.98,"inferenceMs":1.5}""", null);
    static final AtomicReference<String> codeRequest = new AtomicReference<>();
    static final HttpServer codeWorker = fake("CODE", "fake-codeberta", """
            {"worker":"code-a","model":"fake-codeberta","version":"rev2","task":"text-classification",
             "label":"python","score":0.91,"inferenceMs":2.0,
             "topK":[{"label":"python","score":0.91},{"label":"ruby","score":0.05}]}""", codeRequest);
    static final HttpServer imageWorker = fake("IMAGE", "fake-vit", null, null);

    static HttpServer fake(String capability, String model, String predictJson, AtomicReference<String> received) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/predict", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (received != null) received.set(body);
                if (predictJson == null) { // what worker.py sends for an unreadable image
                    reply(exchange, 422, "{\"detail\":[{\"loc\":[\"body\",\"text\"],\"type\":\"invalid_image\"}]}");
                } else {
                    reply(exchange, 200, predictJson);
                }
            });
            server.createContext("/health", exchange -> reply(exchange, 200, "{\"status\":\"healthy\",\"model\":\"" + model
                    + "\",\"revision\":\"rev\",\"capability\":\"" + capability + "\"}"));
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static void reply(com.sun.net.httpserver.HttpExchange exchange, int status, String json) throws IOException {
        byte[] out = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, out.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(out);
        }
    }

    static String url(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @DynamicPropertySource
    static void workers(DynamicPropertyRegistry registry) {
        registry.add("platform.worker.url", () -> url(textWorker));
        registry.add("platform.worker.code.urls", () -> url(codeWorker));
        registry.add("platform.worker.image.urls", () -> url(imageWorker));
    }

    @AfterAll
    static void stop() {
        textWorker.stop(0);
        codeWorker.stop(0);
        imageWorker.stop(0);
    }

    @Value("${local.server.port}")
    int port;

    final HttpClient http = HttpClient.newHttpClient();

    HttpResponse<String> post(String json) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/inference"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void codeRequestGoesToTheCodeWorkerAndReturnsTopK() throws Exception {
        HttpResponse<String> r = post("{\"capability\":\"CODE\",\"input\":\"def add(a, b): return a + b\"}");
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.body().contains("\"capability\":\"CODE\""), r.body());
        assertTrue(r.body().contains("\"worker\":\"code-a\""), r.body());
        assertTrue(r.body().contains("\"label\":\"python\""), r.body());
        assertTrue(r.body().contains("\"topK\":[{\"label\":\"python\",\"score\":0.91}"), r.body());
        assertTrue(codeRequest.get().contains("\"text\":\"def add(a, b): return a + b\""), codeRequest.get());
    }

    @Test
    void textResponseShapeIsUnchanged() throws Exception {
        HttpResponse<String> r = post("{\"capability\":\"TEXT\",\"input\":\"I love this\"}");
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.body().contains("\"worker\":\"text-a\""), r.body());
        assertFalse(r.body().contains("topK"), r.body());
    }

    @Test
    void invalidImageIsA422ClientErrorThroughSpring() throws Exception {
        HttpResponse<String> r = post("{\"capability\":\"IMAGE\",\"input\":\"eA==\"}");
        assertEquals(422, r.statusCode(), r.body());
        assertTrue(r.body().contains("\"error\":\"INVALID_IMAGE\""), r.body());
        assertFalse(r.body().contains("eA=="), r.body());
    }

    @Test
    void capabilitiesListsConfiguredPoolsWithLiveWorkerReports() throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/api/capabilities")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        String body = r.body();
        assertTrue(body.contains("\"defaultCapability\":\"TEXT\""), body);
        assertTrue(body.indexOf("\"capability\":\"TEXT\"") < body.indexOf("\"capability\":\"CODE\""), body);
        assertTrue(body.contains("\"reportedCapability\":\"CODE\""), body);
        assertTrue(body.contains("\"model\":\"fake-codeberta\""), body);
        assertTrue(body.contains("\"healthyCount\":1"), body);
        assertTrue(body.indexOf("\"capability\":\"CODE\"") < body.indexOf("\"capability\":\"IMAGE\""), body);
    }
}
