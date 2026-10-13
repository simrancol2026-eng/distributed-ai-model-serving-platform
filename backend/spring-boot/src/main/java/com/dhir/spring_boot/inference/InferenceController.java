package com.dhir.spring_boot.inference;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.dhir.spring_boot.inference.InferenceDtos.ErrorResponse;
import com.dhir.spring_boot.inference.InferenceDtos.InferenceRequest;
import com.dhir.spring_boot.inference.InferenceDtos.InferenceResponse;
import com.dhir.spring_boot.inference.InferenceDtos.Prediction;
import com.dhir.spring_boot.inference.InferenceDtos.WorkerPrediction;
import com.dhir.spring_boot.inference.WorkerClient.WorkerErrorException;
import com.dhir.spring_boot.inference.WorkerClient.WorkerUnavailableException;
import com.dhir.spring_boot.registry.RegistryDtos.ModelDetail;
import com.dhir.spring_boot.registry.RegistryDtos.ModelVersion;
import com.dhir.spring_boot.registry.RegistryRepository;
import com.dhir.spring_boot.registry.RegistryValidation;
import com.dhir.spring_boot.tracking.RequestTracker;

/**
 * Unified inference API: POST /api/inference.
 *
 * Without "model": validate -> check capability -> next worker of ALL workers (round-robin).
 * With "model" (and optional "version", default "latest"), Sprint 2 version-aware dispatch:
 * validate -> resolve model + version in the registry -> check capability -> keep only workers whose
 * own /health reports exactly that artifact + revision -> next of THOSE (round-robin) -> check the
 * worker's reply names the same artifact + revision. A requested version is never swapped for another.
 *
 *   400 INVALID_REQUEST        input missing/blank/too long; "version" without "model"; malformed id/label
 *   404 MODEL_NOT_FOUND        "model" is not registered
 *   404 VERSION_NOT_FOUND      that version (or any version, for "latest") is not registered
 *   422 CAPABILITY_MISMATCH    requested capability differs from the registered model's capability
 *   422 NO_ELIGIBLE_WORKER     no worker serves the capability / the resolved artifact + revision
 *   503 REGISTRY_UNAVAILABLE   the registry database could not be read
 *   503 WORKER_UNAVAILABLE     the chosen worker is down or timed out (no failover)
 *   502 WORKER_ERROR           the chosen worker answered with an error
 *   502 WORKER_MODEL_MISMATCH  the chosen worker answered with a different artifact/revision than expected
 */
@RestController
public class InferenceController {

    static final int MAX_INPUT_CHARS = 10_000;
    private static final Logger log = LoggerFactory.getLogger(InferenceController.class);

    private final WorkerPool workers;
    private final RegistryRepository registry;
    private final RequestTracker tracker;

    public InferenceController(WorkerPool workers, RegistryRepository registry, RequestTracker tracker) {
        this.workers = workers;
        this.registry = registry;
        this.tracker = tracker;
    }

    /** A registry version resolved for this request. */
    record Target(String modelId, ModelVersion version) {
    }

    /**
     * Every request is tracked (Sprint 2, see RequestTracker): RECEIVED on arrival, DISPATCHED when a
     * worker is chosen (in call()), then SUCCEEDED or FAILED with the exact status and error code returned.
     * Tracking is best-effort and never changes the response.
     */
    @PostMapping("/api/inference")
    public ResponseEntity<?> infer(@RequestBody(required = false) InferenceRequest request) {
        String requestId = UUID.randomUUID().toString();
        long started = System.nanoTime();
        tracker.received(requestId,
                request == null ? null : blankToNull(request.capability()),
                request == null ? null : blankToNull(request.model()),
                request == null ? null : blankToNull(request.version()),
                request == null || request.input() == null ? null : request.input().length());

        ResponseEntity<?> response = handle(request, requestId, started);

        Object body = response.getBody();
        String errorCode = body instanceof ErrorResponse e ? e.error() : null;
        String workerId = body instanceof InferenceResponse r ? r.worker() : null;
        tracker.completed(requestId, response.getStatusCode().value(), errorCode, workerId,
                (System.nanoTime() - started) / 1_000_000);
        return response;
    }

    private ResponseEntity<?> handle(InferenceRequest request, String requestId, long started) {
        if (request == null || request.input() == null || request.input().isBlank()) {
            return error(400, "INVALID_REQUEST", "Field 'input' is required and must not be blank.", requestId);
        }
        if (request.input().length() > MAX_INPUT_CHARS) {
            return error(400, "INVALID_REQUEST",
                    "Field 'input' must be at most " + MAX_INPUT_CHARS + " characters.", requestId);
        }
        // AUTO (or missing) means "any capability"; otherwise it must match.
        String capability = request.capability() == null ? "AUTO"
                : request.capability().trim().toUpperCase(Locale.ROOT);
        String modelId = blankToNull(request.model());
        String version = blankToNull(request.version());

        if (modelId == null && version != null) {
            return error(400, "INVALID_REQUEST", "Field 'version' requires field 'model'.", requestId);
        }
        if (modelId == null) {
            // Unchanged behaviour: any worker, round-robin over all of them.
            if (!capability.equals("AUTO") && !capability.equals(workers.capability())) {
                return error(422, "NO_ELIGIBLE_WORKER", "No worker is registered for capability " + capability
                        + ". Available: " + workers.capability() + ".", requestId);
            }
            return call(workers.next(), request.input(), null, requestId, started);
        }

        // ---- version-aware dispatch ----
        if (!RegistryValidation.isModelId(modelId)) {
            return error(400, "INVALID_REQUEST", "Field 'model' is not a valid model id.", requestId);
        }
        boolean latest = version == null || version.equalsIgnoreCase(RegistryValidation.LATEST);
        if (!latest && !RegistryValidation.isVersionLabel(version)) {
            return error(400, "INVALID_REQUEST", "Field 'version' is not a valid version label.", requestId);
        }

        ModelDetail model;
        Optional<ModelVersion> resolved;
        try {
            Optional<ModelDetail> found = registry.findModel(modelId);
            if (found.isEmpty()) {
                return error(404, "MODEL_NOT_FOUND", "No registered model '" + modelId + "'.", requestId);
            }
            model = found.get();
            resolved = latest ? registry.latestVersion(modelId) : registry.findVersion(modelId, version);
        } catch (DataAccessException e) {
            log.warn("inference failed requestId={} reason=REGISTRY_UNAVAILABLE {}", requestId, e.getMessage());
            return error(503, "REGISTRY_UNAVAILABLE", "The model registry could not be read.", requestId);
        }
        if (!capability.equals("AUTO") && !capability.equals(model.capability())) {
            return error(422, "CAPABILITY_MISMATCH", "Model '" + modelId + "' has capability "
                    + model.capability() + ", not " + capability + ".", requestId);
        }
        if (resolved.isEmpty()) {
            return error(404, "VERSION_NOT_FOUND", latest
                    ? "Model '" + modelId + "' has no registered versions."
                    : "Model '" + modelId + "' has no version '" + version + "'.", requestId);
        }
        ModelVersion v = resolved.get();
        if (!model.capability().equals(workers.capability())) {
            return error(422, "NO_ELIGIBLE_WORKER", "No worker serves capability " + model.capability()
                    + " (model '" + modelId + "').", requestId);
        }
        List<WorkerClient> eligible = workers.eligible(v.artifact(), v.artifactRevision());
        if (eligible.isEmpty()) {
            return error(422, "NO_ELIGIBLE_WORKER", "No worker currently serves model '" + modelId + "' version '"
                    + v.version() + "' (" + v.artifact() + " at revision " + v.artifactRevision() + ").", requestId);
        }
        WorkerClient worker = workers.next(eligible, v.artifact(), v.artifactRevision());
        return call(worker, request.input(), new Target(modelId, v), requestId, started);
    }

    /** Calls one worker and maps the outcome. target == null for requests that named no model. */
    private ResponseEntity<?> call(WorkerClient worker, String input, Target target, String requestId,
            long started) {
        tracker.dispatched(requestId, worker.endpoint(),
                target == null ? null : target.version().version(),
                target == null ? null : target.version().artifact(),
                target == null ? null : target.version().artifactRevision());
        try {
            WorkerPrediction p = worker.predict(input);
            if (target != null && !(target.version().artifact().equals(p.model())
                    && target.version().artifactRevision().equals(p.version()))) {
                worker.forgetServedModel();
                log.warn("inference failed requestId={} routedTo={} reason=WORKER_MODEL_MISMATCH expected={}@{} got={}@{}",
                        requestId, worker.endpoint(), target.version().artifact(),
                        target.version().artifactRevision(), p.model(), p.version());
                return error(502, "WORKER_MODEL_MISMATCH", "The model worker at " + worker.endpoint()
                        + " answered with a different model or revision than requested.", requestId);
            }
            long totalMs = (System.nanoTime() - started) / 1_000_000;
            log.info("inference ok requestId={} routedTo={} worker={} model={} version={} label={} totalMs={}",
                    requestId, worker.endpoint(), p.worker(), target == null ? "-" : target.modelId(),
                    target == null ? "-" : target.version().version(), p.label(), totalMs);
            return ResponseEntity.ok(new InferenceResponse("success", requestId, workers.capability(),
                    p.worker(), worker.endpoint(), p.model(), p.version(), p.task(),
                    new Prediction(p.label(), p.score()), p.inferenceMs(), totalMs,
                    target == null ? null : target.modelId(), target == null ? null : target.version().version()));
        } catch (WorkerUnavailableException e) {
            log.warn("inference failed requestId={} routedTo={} reason=WORKER_UNAVAILABLE {}", requestId,
                    worker.endpoint(), e.getMessage());
            return error(503, "WORKER_UNAVAILABLE", "The model worker at " + worker.endpoint()
                    + " is not reachable. Please try again shortly.", requestId);
        } catch (WorkerErrorException e) {
            log.warn("inference failed requestId={} routedTo={} reason=WORKER_ERROR {}", requestId,
                    worker.endpoint(), e.getMessage());
            return error(502, "WORKER_ERROR", "The model worker at " + worker.endpoint() + " returned an error.",
                    requestId);
        }
    }

    private static ResponseEntity<?> error(int status, String code, String message, String requestId) {
        return ResponseEntity.status(status).body(ErrorResponse.of(code, message, requestId));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
