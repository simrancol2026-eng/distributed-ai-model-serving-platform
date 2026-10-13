package com.dhir.spring_boot.inference;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

/**
 * Unified inference API: POST /api/inference.
 *
 * Phase 1 flow: validate request -> check capability -> call the single worker ->
 * return a normalized response. Errors are returned as JSON with a clear code:
 *   400 INVALID_REQUEST     input missing/empty/too long
 *   422 NO_ELIGIBLE_WORKER  no worker serves the requested capability
 *   503 WORKER_UNAVAILABLE  worker down or timed out
 *   502 WORKER_ERROR        worker answered with an error
 */
@RestController
public class InferenceController {

    static final int MAX_INPUT_CHARS = 10_000;
    private static final Logger log = LoggerFactory.getLogger(InferenceController.class);

    private final WorkerClient workerClient;

    public InferenceController(WorkerClient workerClient) {
        this.workerClient = workerClient;
    }

    @PostMapping("/api/inference")
    public ResponseEntity<?> infer(@RequestBody(required = false) InferenceRequest request) {
        String requestId = UUID.randomUUID().toString();
        long started = System.nanoTime();

        if (request == null || request.input() == null || request.input().isBlank()) {
            return ResponseEntity.badRequest().body(
                    ErrorResponse.of("INVALID_REQUEST", "Field 'input' is required and must not be blank.", requestId));
        }
        if (request.input().length() > MAX_INPUT_CHARS) {
            return ResponseEntity.badRequest().body(ErrorResponse.of("INVALID_REQUEST",
                    "Field 'input' must be at most " + MAX_INPUT_CHARS + " characters.", requestId));
        }

        // Capability matching: AUTO (or missing) means "any worker"; otherwise it must match.
        String capability = request.capability() == null ? "AUTO" : request.capability().trim().toUpperCase();
        if (!capability.equals("AUTO") && !capability.equals(workerClient.capability())) {
            return ResponseEntity.status(422).body(ErrorResponse.of("NO_ELIGIBLE_WORKER",
                    "No worker is registered for capability " + capability + ". Available: "
                            + workerClient.capability() + ".", requestId));
        }

        try {
            WorkerPrediction p = workerClient.predict(request.input());
            long totalMs = (System.nanoTime() - started) / 1_000_000;
            log.info("inference ok requestId={} worker={} label={} totalMs={}", requestId, p.worker(), p.label(),
                    totalMs);
            return ResponseEntity.ok(new InferenceResponse("success", requestId, workerClient.capability(),
                    p.worker(), p.model(), p.version(), p.task(), new Prediction(p.label(), p.score()),
                    p.inferenceMs(), totalMs));
        } catch (WorkerUnavailableException e) {
            log.warn("inference failed requestId={} reason=WORKER_UNAVAILABLE {}", requestId, e.getMessage());
            return ResponseEntity.status(503).body(ErrorResponse.of("WORKER_UNAVAILABLE",
                    "The model worker is not reachable. Please try again shortly.", requestId));
        } catch (WorkerErrorException e) {
            log.warn("inference failed requestId={} reason=WORKER_ERROR {}", requestId, e.getMessage());
            return ResponseEntity.status(502).body(ErrorResponse.of("WORKER_ERROR",
                    "The model worker returned an error.", requestId));
        }
    }
}
