package com.dhir.spring_boot.inference;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;

/**
 * A tiny stand-in for a Python worker process (JDK HTTP server) used by backend tests.
 * Answers POST /predict with a fixed prediction that carries its own worker id, and counts
 * the requests it actually received. It does NOT run a model.
 */
final class FakeWorker {

    final String id;
    final String model;
    final String healthRevision;   // what GET /health claims
    final String predictRevision;  // what POST /predict reports (normally the same)
    final HttpServer server;
    final AtomicInteger hits = new AtomicInteger();
    final AtomicInteger healthHits = new AtomicInteger();

    FakeWorker(String id) {
        this(id, "fake-model", "rev1", "rev1");
    }

    FakeWorker(String id, String model, String revision) {
        this(id, model, revision, revision);
    }

    FakeWorker(String id, String model, String healthRevision, String predictRevision) {
        this.id = id;
        this.model = model;
        this.healthRevision = healthRevision;
        this.predictRevision = predictRevision;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(8));
        server.createContext("/predict", exchange -> {
            exchange.getRequestBody().readAllBytes();
            hits.incrementAndGet();
            byte[] out = ("{\"worker\":\"" + id + "\",\"model\":\"" + model + "\",\"version\":\"" + predictRevision + "\","
                    + "\"task\":\"sentiment-analysis\",\"label\":\"POSITIVE\",\"score\":0.9,\"inferenceMs\":1.0}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.createContext("/health", exchange -> {
            healthHits.incrementAndGet();
            byte[] out = ("{\"status\":\"healthy\",\"worker\":\"" + id + "\",\"model\":\"" + model
                    + "\",\"revision\":\"" + healthRevision + "\",\"capability\":\"TEXT\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    String endpoint() {
        return "127.0.0.1:" + server.getAddress().getPort();
    }

    void stop() {
        server.stop(0);
    }

    /** A URL where nothing listens (port opened and closed again). */
    static String deadUrl() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return "http://127.0.0.1:" + socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
