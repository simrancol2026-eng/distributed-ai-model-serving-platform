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
 * Flow: validate request -> check capability -> pick the next worker (round-robin, see
 * WorkerPool) -> call it -> return a normalized response. Only requests that pass validation
 * and the capability check take a round-robin turn. Errors are JSON with a clear code:
 *   400 INVALID_REQUEST     input missing/empty/too long
 *   422 NO_ELIGIBLE_WORKER  no worker serves the requested capability
 *   503 WORKER_UNAVAILABLE  the chosen worker is down or timed out (no failover to another worker)
 *   502 WORKER_ERROR        the chosen worker answered with an error
 */
@RestController
public class InferenceController {

    static final int MAX_INPUT_CHARS = 10_000;
    private static final Logger log = LoggerFactory.getLogger(InferenceController.class);

    private final WorkerPool workers;

    public InferenceController(WorkerPool workers) {
        this.workers = workers;
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
        if (!capability.equals("AUTO") && !capability.equals(workers.capability())) {
            return ResponseEntity.status(422).body(ErrorResponse.of("NO_ELIGIBLE_WORKER",
                    "No worker is registered for capability " + capability + ". Available: "
                            + workers.capability() + ".", requestId));
        }

        WorkerClient worker = workers.next();
        try {
            WorkerPrediction p = worker.predict(request.input());
            long totalMs = (System.nanoTime() - started) / 1_000_000;
            log.info("inference ok requestId={} routedTo={} worker={} label={} totalMs={}", requestId,
                    worker.endpoint(), p.worker(), p.label(), totalMs);
            return ResponseEntity.ok(new InferenceResponse("success", requestId, workers.capability(),
                    p.worker(), worker.endpoint(), p.model(), p.version(), p.task(),
                    new Prediction(p.label(), p.score()), p.inferenceMs(), totalMs));
        } catch (WorkerUnavailableException e) {
            log.warn("inference failed requestId={} routedTo={} reason=WORKER_UNAVAILABLE {}", requestId,
                    worker.endpoint(), e.getMessage());
            return ResponseEntity.status(503).body(ErrorResponse.of("WORKER_UNAVAILABLE",
                    "The model worker at " + worker.endpoint()
                            + " is not reachable. Please try again shortly.", requestId));
        } catch (WorkerErrorException e) {
            log.warn("inference failed requestId={} routedTo={} reason=WORKER_ERROR {}", requestId,
                    worker.endpoint(), e.getMessage());
            return ResponseEntity.status(502).body(ErrorResponse.of("WORKER_ERROR",
                    "The model worker at " + worker.endpoint() + " returned an error.", requestId));
        }
    }
}
