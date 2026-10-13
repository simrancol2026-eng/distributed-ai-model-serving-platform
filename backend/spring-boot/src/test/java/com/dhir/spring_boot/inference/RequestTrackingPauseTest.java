package com.dhir.spring_boot.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * After a failed tracking write, tracking pauses (platform.tracking.pause-after-failure-ms) instead of
 * making every following request wait on a broken database. Here the pause is 60 s, so a request sent
 * right after the table is back is still served but deliberately not recorded.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RequestTrackingPauseTest {

    static final FakeWorker A = new FakeWorker("worker-p");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("platform.worker.urls", A::url);
        registry.add("platform.tracking.pause-after-failure-ms", () -> "60000");
    }

    @AfterAll
    static void stop() {
        A.stop();
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcClient jdbc;

    final HttpClient http = HttpClient.newHttpClient();

    String inferId() throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/inference"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"input\":\"x\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        Matcher m = Pattern.compile("\"requestId\":\"([^\"]*)\"").matcher(r.body());
        m.find();
        return m.group(1);
    }

    int lookup(String id) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/requests/" + id))
                .GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    @Test
    void afterAFailedWriteTrackingIsPausedButInferenceIsNot() throws Exception {
        assertEquals(200, lookup(inferId()), "tracked normally before the failure");

        jdbc.sql("ALTER TABLE inference_requests RENAME TO inference_requests_off").update();
        try {
            inferId(); // tracking write fails here -> pause starts; the request is still served (200)
        } finally {
            jdbc.sql("ALTER TABLE inference_requests_off RENAME TO inference_requests").update();
        }

        String duringPause = inferId(); // table is back, but writes are skipped while paused
        assertEquals(404, lookup(duringPause), "not recorded during the pause");
    }
}
