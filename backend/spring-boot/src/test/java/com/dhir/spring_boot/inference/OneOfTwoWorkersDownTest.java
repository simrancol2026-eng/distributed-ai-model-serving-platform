package com.dhir.spring_boot.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Two configured workers, one of them not running. There is NO failover: requests routed to
 * the dead worker get 503 naming its endpoint, and the following request still goes to the
 * live worker (round-robin keeps moving).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OneOfTwoWorkersDownTest {

    static final FakeWorker LIVE = new FakeWorker("worker-a");
    static final String DEAD_URL = FakeWorker.deadUrl();

    @DynamicPropertySource
    static void workers(DynamicPropertyRegistry registry) {
        registry.add("platform.worker.urls", () -> LIVE.url() + "," + DEAD_URL);
    }

    @AfterAll
    static void stop() {
        LIVE.stop();
    }

    @Value("${local.server.port}")
    int port;

    HttpResponse<String> post() throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/inference"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"capability\":\"TEXT\",\"input\":\"hi\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void deadWorkerGives503AndTheNextRequestGoesToTheLiveOne() throws Exception {
        String deadEndpoint = DEAD_URL.substring("http://".length());
        int ok = 0;
        int unavailable = 0;
        Integer previous = null;
        for (int i = 0; i < 6; i++) {
            HttpResponse<String> r = post();
            if (r.statusCode() == 200) {
                ok++;
                assertTrue(r.body().contains("\"worker\":\"worker-a\""), r.body());
            } else {
                unavailable++;
                assertEquals(503, r.statusCode(), r.body());
                assertTrue(r.body().contains("WORKER_UNAVAILABLE"), r.body());
                assertTrue(r.body().contains(deadEndpoint), "503 names the endpoint tried: " + r.body());
            }
            if (previous != null) {
                // Strict alternation: no retry on the live worker after a failure, no skipping.
                assertTrue(previous != r.statusCode(), "statuses must alternate");
            }
            previous = r.statusCode();
        }
        assertEquals(3, ok);
        assertEquals(3, unavailable);
        assertEquals(3, LIVE.hits.get());
    }
}
