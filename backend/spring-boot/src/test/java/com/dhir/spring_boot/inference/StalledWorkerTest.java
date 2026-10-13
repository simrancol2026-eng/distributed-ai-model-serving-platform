package com.dhir.spring_boot.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A worker that ACCEPTS the connection but never answers (like a frozen process, kill -STOP).
 * The read timeout must end the request with 503 WORKER_UNAVAILABLE, not 502
 * (Grok TEST REPORT on 133dbe9). The read timeout is shortened to 1.5 s for this test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StalledWorkerTest {

    static final long READ_TIMEOUT_MS = 1500;
    static final ServerSocket STALLED;
    static final List<Socket> HELD = new CopyOnWriteArrayList<>();

    static {
        try {
            STALLED = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        Thread acceptor = new Thread(() -> {
            while (!STALLED.isClosed()) {
                try {
                    HELD.add(STALLED.accept()); // accept, read nothing, write nothing
                } catch (IOException e) {
                    return;
                }
            }
        }, "stalled-worker");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    @DynamicPropertySource
    static void stalledWorker(DynamicPropertyRegistry registry) {
        registry.add("platform.worker.url", () -> "http://127.0.0.1:" + STALLED.getLocalPort());
        registry.add("platform.worker.read-timeout-ms", () -> String.valueOf(READ_TIMEOUT_MS));
    }

    @AfterAll
    static void close() throws IOException {
        STALLED.close();
        for (Socket s : HELD) {
            s.close();
        }
    }

    @Value("${local.server.port}")
    int port;

    @Test
    void workerThatNeverAnswersGives503AfterTheReadTimeout() throws Exception {
        long started = System.nanoTime();
        HttpResponse<String> r = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/inference"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"capability\":\"TEXT\",\"input\":\"hi\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertEquals(503, r.statusCode(), r.body());
        assertTrue(r.body().contains("WORKER_UNAVAILABLE"), r.body());
        assertTrue(r.body().contains("127.0.0.1:" + STALLED.getLocalPort()), "names the endpoint: " + r.body());
        // Bounded by the read timeout: not answered early, and not hanging far beyond it.
        assertTrue(elapsedMs >= READ_TIMEOUT_MS - 100, "elapsed " + elapsedMs + " ms");
        assertTrue(elapsedMs < READ_TIMEOUT_MS + 5000, "elapsed " + elapsedMs + " ms");
    }

    @Test
    void timeoutIsRecognisedAnywhereInTheCauseChain() {
        Exception wrapped = new RuntimeException("I/O error while reading",
                new IOException("read failed", new java.net.SocketTimeoutException("Read timed out")));
        assertTrue(WorkerClient.isTimeout(wrapped));
        assertTrue(!WorkerClient.isTimeout(new RuntimeException("no timeout here", new IOException("other"))));
    }
}
