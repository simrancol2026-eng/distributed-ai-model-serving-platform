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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Real Spring Boot app on a random port with TWO fake worker processes (JDK HTTP servers).
 * Tests the backend's round-robin routing, not the model: scripts/round_robin_e2e_test.py
 * checks the same thing with two real Python workers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RoundRobinApiTest {

    static final FakeWorker A = new FakeWorker("worker-a");
    static final FakeWorker B = new FakeWorker("worker-b");

    @DynamicPropertySource
    static void workers(DynamicPropertyRegistry registry) {
        registry.add("platform.worker.urls", () -> A.url() + "," + B.url());
    }

    @AfterAll
    static void stopWorkers() {
        A.stop();
        B.stop();
    }

    @Value("${local.server.port}")
    int port;

    final HttpClient http = HttpClient.newHttpClient();

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

    String servedBy() throws Exception {
        HttpResponse<String> r = post("{\"capability\":\"TEXT\",\"input\":\"hello\"}");
        assertEquals(200, r.statusCode(), r.body());
        String worker = field(r.body(), "worker");
        String endpoint = field(r.body(), "workerEndpoint");
        // The id the process reported and the endpoint the backend chose must describe the same worker.
        assertEquals(worker.equals("worker-a") ? A.endpoint() : B.endpoint(), endpoint, r.body());
        return worker;
    }

    static String other(String worker) {
        return worker.equals("worker-a") ? "worker-b" : "worker-a";
    }

    @Test
    void successiveRequestsAlternateBetweenTheTwoWorkers() throws Exception {
        int hitsA = A.hits.get();
        int hitsB = B.hits.get();
        String first = servedBy();
        List<String> order = new ArrayList<>(List.of(first));
        for (int i = 0; i < 5; i++) {
            order.add(servedBy());
        }
        // Other tests in this class share the pool, so the first worker can be either one.
        assertEquals(List.of(first, other(first), first, other(first), first, other(first)), order);
        // Each fake worker really received 3 of the 6 requests.
        assertEquals(3, A.hits.get() - hitsA);
        assertEquals(3, B.hits.get() - hitsB);
    }

    @Test
    void rejectedRequestsDoNotTakeATurn() throws Exception {
        String first = servedBy();
        assertEquals(400, post("{\"capability\":\"TEXT\",\"input\":\"  \"}").statusCode());
        assertEquals(422, post("{\"capability\":\"IMAGE\",\"input\":\"x\"}").statusCode());
        assertEquals(other(first), servedBy());
    }

    @Test
    void concurrentRequestsAreSplitEvenly() throws Exception {
        int hitsA = A.hits.get();
        int hitsB = B.hits.get();
        int n = 40;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    start.await();
                    return http.send(request("{\"capability\":\"TEXT\",\"input\":\"hi\"}"),
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
        assertEquals(n / 2, A.hits.get() - hitsA, "worker-a share");
        assertEquals(n / 2, B.hits.get() - hitsB, "worker-b share");
    }

    @Test
    void successResponseNamesTheChosenEndpoint() throws Exception {
        HttpResponse<String> r = post("{\"capability\":\"AUTO\",\"input\":\"hello\"}");
        assertEquals(200, r.statusCode());
        String endpoint = field(r.body(), "workerEndpoint");
        assertTrue(endpoint.equals(A.endpoint()) || endpoint.equals(B.endpoint()), r.body());
    }
}
