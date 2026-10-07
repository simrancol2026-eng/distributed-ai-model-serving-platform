package com.dhir.spring_boot.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.sun.net.httpserver.HttpServer;

/**
 * Runs the real Spring Boot app on a random port against a FAKE worker
 * (a tiny JDK HTTP server). This tests the backend's own logic: validation,
 * capability check, response mapping, error handling and CORS.
 * It does NOT test the real model; scripts/phase1_e2e_test.py does that.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InferenceApiTest {

    static final String GOOD_WORKER_JSON = """
            {"worker":"model-a","model":"fake-model","version":"rev1","task":"sentiment-analysis",
             "label":"POSITIVE","score":0.98,"inferenceMs":12.5}""";

    static final HttpServer fakeWorker;
    static final AtomicInteger fakeStatus = new AtomicInteger(200);
    static final AtomicReference<String> fakeBody = new AtomicReference<>(GOOD_WORKER_JSON);
    static final AtomicReference<String> lastRequestBody = new AtomicReference<>();

    static {
        try {
            fakeWorker = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        fakeWorker.createContext("/predict", exchange -> {
            lastRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = fakeBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(fakeStatus.get(), out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        fakeWorker.start();
    }

    @DynamicPropertySource
    static void workerUrl(DynamicPropertyRegistry registry) {
        registry.add("platform.worker.url", () -> "http://127.0.0.1:" + fakeWorker.getAddress().getPort());
    }

    @AfterAll
    static void stopFakeWorker() {
        fakeWorker.stop(0);
    }

    @Value("${local.server.port}")
    int port;

    final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void resetFake() {
        fakeStatus.set(200);
        fakeBody.set(GOOD_WORKER_JSON);
    }

    HttpResponse<String> post(String json) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/inference"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void healthEndpointStillWorks() throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/api/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode());
        assertEquals("Backend is healthy", r.body());
    }

    @Test
    void successfulInferenceIsForwardedAndNormalized() throws Exception {
        HttpResponse<String> r = post("{\"capability\":\"TEXT\",\"input\":\"I love this\"}");
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.body().contains("\"status\":\"success\""), r.body());
        assertTrue(r.body().contains("\"label\":\"POSITIVE\""), r.body());
        assertTrue(r.body().contains("\"model\":\"fake-model\""), r.body());
        assertTrue(r.body().contains("\"worker\":\"model-a\""), r.body());
        assertTrue(r.body().contains("\"requestId\""), r.body());
        // The text really reached the worker as JSON {"text": ...}
        assertTrue(lastRequestBody.get().contains("\"text\":\"I love this\""), lastRequestBody.get());
    }

    @Test
    void autoCapabilityIsAccepted() throws Exception {
        assertEquals(200, post("{\"capability\":\"AUTO\",\"input\":\"hello\"}").statusCode());
    }

    @Test
    void blankInputIsRejectedWith400() throws Exception {
        HttpResponse<String> r = post("{\"capability\":\"TEXT\",\"input\":\"   \"}");
        assertEquals(400, r.statusCode());
        assertTrue(r.body().contains("INVALID_REQUEST"), r.body());
    }

    @Test
    void tooLongInputIsRejectedWith400() throws Exception {
        String longText = "a".repeat(InferenceController.MAX_INPUT_CHARS + 1);
        assertEquals(400, post("{\"capability\":\"TEXT\",\"input\":\"" + longText + "\"}").statusCode());
    }

    @Test
    void unsupportedCapabilityIsRejectedWith422() throws Exception {
        HttpResponse<String> r = post("{\"capability\":\"IMAGE\",\"input\":\"x\"}");
        assertEquals(422, r.statusCode());
        assertTrue(r.body().contains("NO_ELIGIBLE_WORKER"), r.body());
    }

    @Test
    void workerHttpErrorBecomes502() throws Exception {
        fakeStatus.set(500);
        fakeBody.set("{\"detail\":\"boom\"}");
        HttpResponse<String> r = post("{\"capability\":\"TEXT\",\"input\":\"hello\"}");
        assertEquals(502, r.statusCode());
        assertTrue(r.body().contains("WORKER_ERROR"), r.body());
    }

    @Test
    void malformedWorkerResponseBecomes502() throws Exception {
        fakeBody.set("not json");
        assertEquals(502, post("{\"capability\":\"TEXT\",\"input\":\"hello\"}").statusCode());
    }

    @Test
    void corsAllowsFrontendOriginOnly() throws Exception {
        HttpResponse<String> allowed = preflight("http://localhost:5173");
        assertEquals("http://localhost:5173",
                allowed.headers().firstValue("Access-Control-Allow-Origin").orElse(null));

        HttpResponse<String> other = preflight("http://evil.example");
        assertTrue(other.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
    }

    HttpResponse<String> preflight(String origin) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/inference"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", origin)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "Content-Type")
                .build(), HttpResponse.BodyHandlers.ofString());
    }
}
