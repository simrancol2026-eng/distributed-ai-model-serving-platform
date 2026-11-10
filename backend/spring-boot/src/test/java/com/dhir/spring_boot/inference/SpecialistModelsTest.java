package com.dhir.spring_boot.inference;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import com.dhir.spring_boot.inference.InferenceDtos.*;

/**
 * Specialist models (D-049): one WorkerPool per capability. Deterministic, no sockets and no models:
 * fake workers answer like the real CODE/IMAGE workers' contract, so this proves routing and response
 * mapping only, not that CodeBERTa or ViT produce correct labels.
 */
class SpecialistModelsTest {

    /** A worker that records what it received and answers with a fixed prediction for its task. */
    static class TaskWorker extends WorkerClient {
        final String task;
        final List<Prediction> topK;
        int calls;
        RuntimeException failure;

        TaskWorker(int port, String task, List<Prediction> topK) {
            super("http://localhost:" + port, FailoverControllerTest.unused(), FailoverControllerTest.unused());
            this.task = task;
            this.topK = topK;
        }

        @Override public WorkerPrediction predict(String text) { return answer(); }
        @Override WorkerPrediction predict(String text, DispatchBudget budget) { return answer(); }

        private WorkerPrediction answer() {
            calls++;
            if (failure != null) throw failure;
            String label = topK == null ? "POSITIVE" : topK.get(0).label();
            return new WorkerPrediction(endpoint(), "model-" + task, "rev", task, label, 0.9, 1, topK);
        }
    }

    static final List<Prediction> CODE_TOP = List.of(new Prediction("python", 0.9), new Prediction("go", 0.05));
    static final List<Prediction> IMAGE_TOP = List.of(new Prediction("tabby cat", 0.7), new Prediction("lynx", 0.1));

    static WorkerPool pool(String capability, WorkerPool.Policy policy, TaskWorker... workers) {
        var observations = new LinkedHashMap<WorkerClient, WorkerHealth>();
        for (var w : workers) {
            var health = new WorkerHealth(() -> new WorkerHealth.Probe(true, capability,
                    new WorkerClient.ServedModel("model-" + w.task, "rev")), System::nanoTime, 10_000_000_000L);
            health.pollOnce();
            observations.put(w, health);
        }
        return new WorkerPool(List.<WorkerClient>of(workers), policy, observations, capability);
    }

    static ResponseEntity<?> infer(WorkerPools pools, FailoverSettings failover, String capability, String input) {
        return new InferenceController(pools, null, FailoverControllerTest.tracker(), failover)
                .infer(new InferenceRequest(capability, input, null, null));
    }

    static ResponseEntity<?> infer(WorkerPools pools, String capability, String input) {
        return infer(pools, new FailoverSettings(false, 10000), capability, input);
    }

    final TaskWorker text = new TaskWorker(8001, "sentiment-analysis", null);
    final TaskWorker code = new TaskWorker(8101, "text-classification", CODE_TOP);
    final TaskWorker image = new TaskWorker(8201, "image-classification", IMAGE_TOP);
    final WorkerPools pools = new WorkerPools(pool("TEXT", WorkerPool.Policy.ROUND_ROBIN, text),
            Map.of("CODE", pool("CODE", WorkerPool.Policy.ROUND_ROBIN, code),
                    "IMAGE", pool("IMAGE", WorkerPool.Policy.ROUND_ROBIN, image)));

    @Test void eachCapabilityReachesOnlyItsOwnWorkers() {
        var r = (InferenceResponse) infer(pools, "CODE", "def f(): pass").getBody();
        assertEquals("CODE", r.capability());
        assertEquals(code.endpoint(), r.workerEndpoint());
        assertEquals("python", r.prediction().label());
        assertEquals(CODE_TOP, r.topK());

        r = (InferenceResponse) infer(pools, "image", "iVBORw0KGgo=").getBody(); // case-insensitive, as before
        assertEquals("IMAGE", r.capability());
        assertEquals(IMAGE_TOP, r.topK());

        assertEquals(List.of(0, 1, 1), List.of(text.calls, code.calls, image.calls));
    }

    @Test void textAndAutoAreUnchangedAndHaveNoTopK() {
        for (String capability : new String[] { "TEXT", "AUTO", null }) {
            var r = (InferenceResponse) infer(pools, capability, "I love this").getBody();
            assertEquals("TEXT", r.capability());
            assertEquals(text.endpoint(), r.workerEndpoint());
            assertNull(r.topK());
        }
        assertEquals(0, code.calls + image.calls);
    }

    @Test void unconfiguredCapabilityIs422AndListsWhatIsAvailable() {
        var onlyTextAndCode = new WorkerPools(pool("TEXT", WorkerPool.Policy.ROUND_ROBIN, text),
                Map.of("CODE", pool("CODE", WorkerPool.Policy.ROUND_ROBIN, code)));
        var response = infer(onlyTextAndCode, "IMAGE", "abc");
        assertEquals(422, response.getStatusCode().value());
        var error = (ErrorResponse) response.getBody();
        assertEquals("NO_ELIGIBLE_WORKER", error.error());
        assertTrue(error.message().contains("Available: TEXT, CODE."), error.message());
        assertEquals(0, text.calls + code.calls);
    }

    @Test void textOnlyConfigurationKeepsTheOriginalMessage() {
        var response = infer(WorkerPools.of(pool("TEXT", WorkerPool.Policy.ROUND_ROBIN, text)), "CODE", "x");
        assertEquals(422, response.getStatusCode().value());
        assertEquals("No worker is registered for capability CODE. Available: TEXT.",
                ((ErrorResponse) response.getBody()).message());
    }

    @Test void onlyImageInputMayExceedTheTextLimit() {
        String big = "A".repeat(InferenceController.MAX_INPUT_CHARS + 1);
        assertEquals(200, infer(pools, "IMAGE", big).getStatusCode().value());
        assertEquals(400, infer(pools, "TEXT", big).getStatusCode().value());
        assertEquals(400, infer(pools, "CODE", big).getStatusCode().value());
        assertEquals(400, infer(pools, "AUTO", big).getStatusCode().value());
        String tooBig = "A".repeat(InferenceController.MAX_IMAGE_INPUT_CHARS + 1);
        assertEquals(400, infer(pools, "IMAGE", tooBig).getStatusCode().value());
        assertEquals(1, image.calls);
        assertEquals(0, text.calls + code.calls);
    }

    @Test void failoverWorksInsideASpecialistPoolAndKeepsTopK() {
        var codeA = new TaskWorker(8101, "text-classification", CODE_TOP);
        var codeB = new TaskWorker(8102, "text-classification", CODE_TOP);
        codeA.failure = FailoverControllerTest.refused();
        var withFailover = new WorkerPools(pool("TEXT", WorkerPool.Policy.ROUND_ROBIN, text),
                Map.of("CODE", pool("CODE", WorkerPool.Policy.ROUND_ROBIN, codeA, codeB)));
        var response = infer(withFailover, new FailoverSettings(true, 10000), "CODE", "print(1)");
        assertEquals(200, response.getStatusCode().value());
        var r = (InferenceResponse) response.getBody();
        assertEquals(codeB.endpoint(), r.workerEndpoint());
        assertEquals(2, r.routing().attempts().size());
        assertEquals(CODE_TOP, r.topK());
        assertEquals(0, text.calls);
    }

    @Test void healthLatencyPolicyIgnoresWorkersReportingAnotherCapability() {
        // A CODE pool whose only worker reports TEXT on /health (misconfigured URL) must not be used.
        var misconfigured = new TaskWorker(8101, "sentiment-analysis", null);
        var health = new WorkerHealth(() -> new WorkerHealth.Probe(true, "TEXT",
                new WorkerClient.ServedModel("model-x", "rev")), System::nanoTime, 10_000_000_000L);
        health.pollOnce();
        var codePool = new WorkerPool(List.<WorkerClient>of(misconfigured), WorkerPool.Policy.HEALTH_LATENCY,
                Map.of(misconfigured, health), "CODE");
        var response = infer(new WorkerPools(pool("TEXT", WorkerPool.Policy.ROUND_ROBIN, text), Map.of("CODE", codePool)),
                "CODE", "print(1)");
        assertEquals(422, response.getStatusCode().value());
        assertEquals(0, misconfigured.calls);
    }

    @Test void aCapabilityCannotBeConfiguredTwice() {
        var textPool = pool("TEXT", WorkerPool.Policy.ROUND_ROBIN, text);
        assertThrows(IllegalStateException.class, () -> new WorkerPools(textPool, Map.of("TEXT", textPool)));
    }

    @Test void blankSpecialistUrlsMeanNoExtraPools() {
        var textPool = new WorkerPool("http://localhost:8001", "TEXT", 100, 100);
        var configured = new WorkerPools(textPool, " ", "", 100, 100, 100, 0, "ROUND_ROBIN",
                new FailoverSettings(false, 10000));
        assertEquals(List.of("TEXT"), List.copyOf(configured.all().keySet()));
        assertSame(textPool, configured.forCapability("AUTO"));
        assertNull(configured.forCapability("CODE"));
    }

    @Test void configuredSpecialistUrlsBuildPoolsInFixedOrder() {
        var textPool = new WorkerPool("http://localhost:8001", "TEXT", 100, 100);
        var configured = new WorkerPools(textPool, "http://localhost:8101,http://localhost:8102",
                "http://localhost:8201", 100, 100, 100, 0, "LEAST_IN_FLIGHT", new FailoverSettings(false, 10000));
        try {
            assertEquals(List.of("TEXT", "CODE", "IMAGE"), List.copyOf(configured.all().keySet()));
            assertEquals(List.of("localhost:8101", "localhost:8102"), configured.forCapability("CODE").endpoints());
            assertEquals("CODE", configured.forCapability("CODE").capability());
            assertEquals("LEAST_IN_FLIGHT", configured.forCapability("IMAGE").policy());
        } finally {
            configured.close();
        }
    }
}
