package com.dhir.spring_boot.inference;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.dhir.spring_boot.inference.InferenceDtos.*;

/** Dispatch tests with deterministic fake workers, not medical/model accuracy evidence. */
class AutoCapabilityTest {
    static class MedicalMetadataWorker extends SpecialistModelsTest.TaskWorker {
        Boolean researchOnly;
        String scoreMeaning;
        MedicalMetadataWorker(int port, Boolean researchOnly, String scoreMeaning) {
            super(port, "chest-xray", List.of(new Prediction("research-label", .5)));
            reports = "HEALTHCARE";
            this.researchOnly = researchOnly;
            this.scoreMeaning = scoreMeaning;
        }
        private WorkerPrediction metadata(WorkerPrediction p) {
            return new WorkerPrediction(p.worker(), p.model(), p.version(), p.task(), p.label(), p.score(),
                    p.inferenceMs(), p.topK(), researchOnly, scoreMeaning);
        }
        @Override public WorkerPrediction predict(String input) { return metadata(super.predict(input)); }
        @Override WorkerPrediction predict(String input, DispatchBudget budget) {
            return metadata(super.predict(input, budget));
        }
    }
    final SpecialistModelsTest.TaskWorker text = new SpecialistModelsTest.TaskWorker(8001, "sentiment-analysis", null);
    final SpecialistModelsTest.TaskWorker code = new SpecialistModelsTest.TaskWorker(8101, "text-classification", SpecialistModelsTest.CODE_TOP);
    final SpecialistModelsTest.TaskWorker image = new SpecialistModelsTest.TaskWorker(8201, "image-classification", SpecialistModelsTest.IMAGE_TOP);
    final SpecialistModelsTest.TaskWorker healthcare = new SpecialistModelsTest.TaskWorker(8301, "chest-xray", List.of(new Prediction("research-label", .5)));

    WorkerPools pools() {
        healthcare.reports = "HEALTHCARE";
        return new WorkerPools(SpecialistModelsTest.pool("TEXT", WorkerPool.Policy.ROUND_ROBIN, text), Map.of(
                "CODE", SpecialistModelsTest.pool("CODE", WorkerPool.Policy.ROUND_ROBIN, code),
                "IMAGE", SpecialistModelsTest.pool("IMAGE", WorkerPool.Policy.ROUND_ROBIN, image),
                "HEALTHCARE", SpecialistModelsTest.pool("HEALTHCARE", WorkerPool.Policy.ROUND_ROBIN, healthcare)));
    }
    org.springframework.http.ResponseEntity<?> infer(String capability, String input, String task) {
        return new InferenceController(pools(), null, FailoverControllerTest.tracker(), new FailoverSettings(false, 10000))
                .infer(new InferenceRequest(capability, input, null, null, task));
    }

    @Test void detectsTextCodeAndImageButNeverInfersMedicalIntent() {
        for (String[] sample : new String[][] {
                {"I love this platform", "TEXT"}, {"def add(a, b): return a+b", "CODE"},
                {"#include <stdio.h>\nint main() { return 0; }", "CODE"},
                {"const value = 1;", "CODE"}, {"fn main() {}", "CODE"},
                {"SELECT * FROM users", "CODE"}, {"data:image/png;base64,iVBORw0KGgo=", "IMAGE"}}) {
            var response = infer("AUTO", sample[0], null);
            assertEquals(200, response.getStatusCode().value(), sample[0]);
            assertEquals(sample[1], ((InferenceResponse) response.getBody()).capability());
        }
        assertEquals(0, healthcare.calls);
    }

    @Test void taskHintsReachEveryPoolAndConflictingHintsFailBeforeDispatch() {
        for (String[] task : new String[][] {{"sentiment-analysis", "TEXT"}, {"code-language", "CODE"},
                {"image-classification", "IMAGE"}, {"chest-xray", "HEALTHCARE"}}) {
            var response = infer("AUTO", "input", task[0]);
            assertEquals(200, response.getStatusCode().value());
            assertEquals(task[1], ((InferenceResponse) response.getBody()).capability());
        }
        var conflict = infer("IMAGE", "input", "chest-xray");
        assertEquals(422, conflict.getStatusCode().value());
        assertEquals("CAPABILITY_MISMATCH", ((ErrorResponse) conflict.getBody()).error());
        assertEquals(400, infer("AUTO", "input", "unsupported-task").getStatusCode().value());
        assertEquals(4, text.calls + code.calls + image.calls + healthcare.calls);
    }

    @Test void ambiguousInputRequiresHintAndMissingMedicalPoolNeverFallsBack() {
        var ambiguous = infer("AUTO", "print('hello')", null);
        assertEquals(422, ambiguous.getStatusCode().value());
        assertEquals("AUTO_AMBIGUOUS", ((ErrorResponse) ambiguous.getBody()).error());
        assertNotNull(((ErrorResponse) ambiguous.getBody()).routing());
        assertNull(((ErrorResponse) ambiguous.getBody()).routing().chosen());
        var controller = new InferenceController(WorkerPools.of(SpecialistModelsTest.pool("TEXT", WorkerPool.Policy.ROUND_ROBIN, text)),
                null, FailoverControllerTest.tracker(), new FailoverSettings(false, 10000));
        var unavailable = controller.infer(new InferenceRequest("AUTO", "image", null, null, "chest-xray"));
        assertEquals(422, unavailable.getStatusCode().value());
        assertEquals("NO_ELIGIBLE_WORKER", ((ErrorResponse) unavailable.getBody()).error());
        assertEquals(0, text.calls);
    }

    @Test void medicalPoolRejectsImageNetTaskAndAllowsImageSizeLimit() {
        var wrong = new SpecialistModelsTest.TaskWorker(8302, "image-classification", SpecialistModelsTest.IMAGE_TOP);
        wrong.reports = "HEALTHCARE";
        var pools = new WorkerPools(SpecialistModelsTest.pool("TEXT", WorkerPool.Policy.ROUND_ROBIN, text),
                Map.of("HEALTHCARE", SpecialistModelsTest.pool("HEALTHCARE", WorkerPool.Policy.ROUND_ROBIN, wrong)));
        var controller = new InferenceController(pools, null, FailoverControllerTest.tracker(), new FailoverSettings(false, 10000));
        var mismatch = controller.infer(new InferenceRequest("HEALTHCARE", "input", null, null));
        assertEquals(502, mismatch.getStatusCode().value());
        assertEquals("WORKER_MODEL_MISMATCH", ((ErrorResponse) mismatch.getBody()).error());
        assertEquals(200, infer("AUTO", "A".repeat(10001), "chest-xray").getStatusCode().value());
        assertEquals(400, infer("AUTO", "A".repeat(10001), "code-language").getStatusCode().value());
    }

    @Test void capabilitySnapshotShowsActualReservationsConfigurationAndHealthFailures() {
        class Probed extends SpecialistModelsTest.TaskWorker {
            boolean reachable = true;
            Probed(int port) { super(port, "sentiment-analysis", null); }
            @Override public WorkerHealth.Probe probeHealth() {
                if (!reachable) throw new IllegalStateException("unreachable");
                return new WorkerHealth.Probe(true, "TEXT", new WorkerClient.ServedModel("real-reported-id", "revision"));
            }
        }
        var worker = new Probed(8401);
        var pool = SpecialistModelsTest.pool("TEXT", WorkerPool.Policy.LEAST_IN_FLIGHT, worker);
        var controller = new CapabilitiesController(WorkerPools.of(pool), new FailoverSettings(true, 5000));
        try (var reservation = pool.reserve()) {
            var snapshot = controller.capabilities();
            assertTrue(snapshot.failoverEnabled());
            assertEquals(5000, snapshot.budgetMs());
            assertEquals("LEAST_IN_FLIGHT", snapshot.capabilities().get(0).policy());
            assertEquals(1, snapshot.capabilities().get(0).workers().get(0).inFlight());
            assertEquals("real-reported-id", snapshot.capabilities().get(0).workers().get(0).model());
            worker.reachable = false;
            snapshot = controller.capabilities();
            assertEquals(0, snapshot.capabilities().get(0).healthyCount());
            assertFalse(snapshot.capabilities().get(0).workers().get(0).reachable());
            assertNull(snapshot.capabilities().get(0).workers().get(0).model());
        }
        assertEquals(0, controller.capabilities().capabilities().get(0).workers().get(0).inFlight());
    }

    @Test void jsonTaskHintAndNamedModelAutoPreserveExactVersion() {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        var request = mapper.readValue("""
                {"capability":"AUTO","input":"image","model":"chest-model","task":"chest-xray"}
                """, InferenceRequest.class);
        assertEquals("chest-xray", request.task());
        assertNull(mapper.readValue("{\"input\":\"hello\"}", InferenceRequest.class).task());
        String revision = "a".repeat(40);
        var version = new com.dhir.spring_boot.registry.RegistryDtos.ModelVersion(1, "chest-model", "v1", "chest-artifact",
                revision, "torch", null, "2026-11-14T00:00:00Z");
        var registry = new com.dhir.spring_boot.registry.RegistryRepository(null) {
            @Override public java.util.Optional<com.dhir.spring_boot.registry.RegistryDtos.ModelDetail> findModel(String id) {
                return java.util.Optional.of(new com.dhir.spring_boot.registry.RegistryDtos.ModelDetail(
                        id, id, "chest-xray", "HEALTHCARE", null, "2026-11-14T00:00:00Z", 1, version));
            }
            @Override public java.util.Optional<com.dhir.spring_boot.registry.RegistryDtos.ModelVersion> latestVersion(String id) {
                return java.util.Optional.of(version);
            }
        };
        var named = new SpecialistModelsTest.TaskWorker(8402, "chest-xray", List.of(new Prediction("research-label", .5))) {
            @Override public java.util.Optional<WorkerClient.ServedModel> servedModel(long age) {
                return java.util.Optional.of(new WorkerClient.ServedModel("chest-artifact", revision));
            }
            @Override public WorkerPrediction predict(String input) {
                return new WorkerPrediction("medical", "chest-artifact", revision, "chest-xray", "research-label", .5, 1, topK);
            }
        };
        named.reports = "HEALTHCARE";
        var pools = new WorkerPools(SpecialistModelsTest.pool("TEXT", WorkerPool.Policy.ROUND_ROBIN, text),
                Map.of("HEALTHCARE", SpecialistModelsTest.pool("HEALTHCARE", WorkerPool.Policy.ROUND_ROBIN, named)));
        var controller = new InferenceController(pools, registry, FailoverControllerTest.tracker(), new FailoverSettings(false, 10000));
        var result = (InferenceResponse) controller.infer(request).getBody();
        assertEquals("HEALTHCARE", result.capability());
        assertEquals(revision, result.version());
        assertEquals("v1", result.modelVersion());
        var conflict = controller.infer(new InferenceRequest("AUTO", "input", "chest-model", null, "image-classification"));
        assertEquals(422, conflict.getStatusCode().value());
        assertEquals("CAPABILITY_MISMATCH", ((ErrorResponse) conflict.getBody()).error());
    }

    @Test void medicalMetadataSurvivesJsonAndNormalDispatchWithLegacyFallback() {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        var workerJson = mapper.readValue("""
                {"worker":"chest-a","model":"chest-model","version":"revision","task":"chest-xray",
                 "label":"research-label","score":0.5,"inferenceMs":1,"researchOnly":true,
                 "scoreMeaning":"Independent research scores, not a diagnosis"}
                """, WorkerPrediction.class);
        assertEquals(Boolean.TRUE, workerJson.researchOnly());
        assertEquals("Independent research scores, not a diagnosis", workerJson.scoreMeaning());
        var worker = new MedicalMetadataWorker(8501, workerJson.researchOnly(), workerJson.scoreMeaning());
        var pools = new WorkerPools(SpecialistModelsTest.pool("TEXT", WorkerPool.Policy.ROUND_ROBIN, text),
                Map.of("HEALTHCARE", SpecialistModelsTest.pool("HEALTHCARE", WorkerPool.Policy.ROUND_ROBIN, worker)));
        var controller = new InferenceController(pools, null, FailoverControllerTest.tracker(), new FailoverSettings(false, 10000));
        var request = new InferenceRequest("AUTO", "image", null, null, "chest-xray");
        var response = (InferenceResponse) controller.infer(request).getBody();
        assertEquals(Boolean.TRUE, response.researchOnly());
        assertEquals(workerJson.scoreMeaning(), response.scoreMeaning());
        var json = mapper.readTree(mapper.writeValueAsString(response));
        assertTrue(json.get("researchOnly").asBoolean());
        assertEquals(workerJson.scoreMeaning(), json.get("scoreMeaning").asText());

        for (Boolean flag : new Boolean[] { null, false, true }) {
            worker.researchOnly = flag;
            worker.scoreMeaning = flag == Boolean.FALSE ? "Clinical diagnosis" : "  ";
            response = (InferenceResponse) controller.infer(request).getBody();
            assertEquals(Boolean.TRUE, response.researchOnly());
            assertEquals(InferenceController.HEALTHCARE_SCORE_MEANING, response.scoreMeaning());
        }
    }

    @Test void alternateWorkerFailoverReconstructionPreservesMedicalMetadata() {
        var first = new MedicalMetadataWorker(8502, true, "First worker metadata");
        first.failure = FailoverControllerTest.refused();
        var alternate = new MedicalMetadataWorker(8503, true, "Independent research scores, not a diagnosis");
        var pools = new WorkerPools(SpecialistModelsTest.pool("TEXT", WorkerPool.Policy.ROUND_ROBIN, text),
                Map.of("HEALTHCARE", SpecialistModelsTest.pool("HEALTHCARE", WorkerPool.Policy.ROUND_ROBIN, first, alternate)));
        var controller = new InferenceController(pools, null, FailoverControllerTest.tracker(), new FailoverSettings(true, 10000));
        var result = (InferenceResponse) controller.infer(new InferenceRequest("HEALTHCARE", "image", null, null)).getBody();
        assertEquals(1, first.calls);
        assertEquals(1, alternate.calls);
        assertEquals(alternate.endpoint(), result.workerEndpoint());
        assertEquals(List.of("FAILED", "SUCCEEDED"), result.routing().attempts().stream().map(RoutingDecision.Attempt::outcome).toList());
        assertEquals(Boolean.TRUE, result.researchOnly());
        assertEquals(alternate.scoreMeaning, result.scoreMeaning());
        assertEquals(alternate.topK, result.topK());
    }

    @Test void nonHealthcareResponseOmitsMissingMedicalMetadata() {
        var result = (InferenceResponse) infer("TEXT", "I love it", null).getBody();
        assertNull(result.researchOnly());
        assertNull(result.scoreMeaning());
        var json = tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(result));
        assertFalse(json.has("researchOnly"));
        assertFalse(json.has("scoreMeaning"));
    }
}
