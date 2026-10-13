package com.dhir.spring_boot.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The worker URL points at a port where nothing is listening: the API must answer 503, not crash. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkerDownTest {

    @DynamicPropertySource
    static void deadWorker(DynamicPropertyRegistry registry) throws Exception {
        int freePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            freePort = socket.getLocalPort(); // closed again immediately -> nothing listens there
        }
        registry.add("platform.worker.url", () -> "http://127.0.0.1:" + freePort);
    }

    @Value("${local.server.port}")
    int port;

    @Test
    void unreachableWorkerGives503() throws Exception {
        HttpResponse<String> r = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/inference"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"capability\":\"TEXT\",\"input\":\"hi\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(503, r.statusCode(), r.body());
        assertTrue(r.body().contains("WORKER_UNAVAILABLE"), r.body());
    }
}
