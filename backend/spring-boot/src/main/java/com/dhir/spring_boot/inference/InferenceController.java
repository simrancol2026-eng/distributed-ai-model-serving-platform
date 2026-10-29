package com.dhir.spring_boot.inference;

import java.util.ArrayList;
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
 *
 * Every request also gets a RoutingDecision (Sprint 2 #5, D-044): logged as one
 * "routing decision requestId=..." line, stored with the tracking row, and returned in the success body.
 */
@RestController
public class InferenceController {

    static final int MAX_INPUT_CHARS = 10_000;
    private static final Logger log = LoggerFactory.getLogger(InferenceController.class);

    private final WorkerPool workers;
    private final RegistryRepository registry;
    private final RequestTracker tracker;
    private final FailoverSettings failover;

    public InferenceController(WorkerPool workers, RegistryRepository registry, RequestTracker tracker) {
        this(workers, registry, tracker, new FailoverSettings(false, 10000));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public InferenceController(WorkerPool workers, RegistryRepository registry, RequestTracker tracker,
            FailoverSettings failover) {
        this.workers = workers;
        this.registry = registry;
        this.tracker = tracker;
        this.failover = failover;
    }

    /** A registry version resolved for this request. */
    record Target(String modelId, ModelVersion version) {
    }

    /** The response plus the routing decision that produced it. */
    record Routed(ResponseEntity<?> response, RoutingDecision decision) {
    }

    /** Max stored length of routing_decision (V3 column is VARCHAR(8000)). */
    static final int MAX_DECISION_JSON = 8000;

    /**
     * Every request is tracked (Sprint 2, see RequestTracker): RECEIVED on arrival, DISPATCHED when a
     * worker is chosen (in call()), then SUCCEEDED or FAILED with the exact status and error code returned.
     * Tracking is best-effort and never changes the response.
     */
    @PostMapping("/api/inference")
    public ResponseEntity<?> infer(@RequestBody(required = false) InferenceRequest request) {
        String requestId = UUID.randomUUID().toString();
        long started = System.nanoTime();
        RequestTracker.Trace trace = tracker.received(requestId,
                request == null ? null : blankToNull(request.capability()),
                request == null ? null : blankToNull(request.model()),
                request == null ? null : blankToNull(request.version()),
                request == null || request.input() == null ? null : request.input().length());

        Routed routed = handle(request, trace, started);
        ResponseEntity<?> response = routed.response();
        RoutingDecision decision = routed.decision();
        log.info("routing decision requestId={} {}", requestId, decision.toLogString());

        Object body = response.getBody();
        String errorCode = body instanceof ErrorResponse e ? e.error() : null;
        String workerId = body instanceof InferenceResponse r ? r.worker() : null;
        tracker.completed(trace, response.getStatusCode().value(), errorCode, workerId,
                (System.nanoTime() - started) / 1_000_000, decision.toJson(MAX_DECISION_JSON), decision.chosen());
        return response;
    }

    private Routed handle(InferenceRequest request, RequestTracker.Trace trace, long started) {
        String requestId = trace.requestId();
        if (request == null || request.input() == null || request.input().isBlank()) {
            return rejected(400, "INVALID_REQUEST", "Field 'input' is required and must not be blank.", requestId);
        }
        if (request.input().length() > MAX_INPUT_CHARS) {
            return rejected(400, "INVALID_REQUEST",
                    "Field 'input' must be at most " + MAX_INPUT_CHARS + " characters.", requestId);
        }
        // AUTO (or missing) means "any capability"; otherwise it must match.
        String capability = request.capability() == null ? "AUTO"
                : request.capability().trim().toUpperCase(Locale.ROOT);
        String modelId = blankToNull(request.model());
        String version = blankToNull(request.version());

        if (modelId == null && version != null) {
            return rejected(400, "INVALID_REQUEST", "Field 'version' requires field 'model'.", requestId);
        }
        if (modelId == null) {
            // Any configured worker; the configured policy chooses within this set.
            if (!capability.equals("AUTO") && !capability.equals(workers.capability())) {
                return rejected(422, "NO_ELIGIBLE_WORKER", "No worker is registered for capability " + capability
                        + ". Available: " + workers.capability() + ".", requestId);
            }
            if (failover.enabled()) return failoverCall(request.input(), null, trace, started);
            if (workers.healthAware()) return healthyCall(request.input(), null, trace, started);
            try (WorkerPool.Reservation reservation = workers.reserve()) {
                WorkerClient worker = reservation.worker();
                List<RoutingDecision.Candidate> all = new ArrayList<>();
                for (WorkerClient w : workers.all()) {
                    // Identity is not checked here: any worker can serve a request that names no model.
                    all.add(new RoutingDecision.Candidate(w.endpoint(), true, RoutingDecision.ANY_WORKER, null));
                }
                RoutingDecision decision = new RoutingDecision(RoutingDecision.ROUND_ROBIN_ALL, null, workers.size(),
                        all.size(), all, worker.endpoint(),
                        "no model requested: " + workers.policyDescription() + " over all " + all.size() + " worker(s)").withSelection(workers.policy(), reservation.before());
                return new Routed(call(worker, request.input(), null, trace, started, decision), decision);
            }
        }

        // ---- version-aware dispatch ----
        if (!RegistryValidation.isModelId(modelId)) {
            return rejected(400, "INVALID_REQUEST", "Field 'model' is not a valid model id.", requestId);
        }
        boolean latest = version == null || version.equalsIgnoreCase(RegistryValidation.LATEST);
        if (!latest && !RegistryValidation.isVersionLabel(version)) {
            return rejected(400, "INVALID_REQUEST", "Field 'version' is not a valid version label.", requestId);
        }

        ModelDetail model;
        Optional<ModelVersion> resolved;
        try {
            Optional<ModelDetail> found = registry.findModel(modelId);
            if (found.isEmpty()) {
                return rejected(404, "MODEL_NOT_FOUND", "No registered model '" + modelId + "'.", requestId);
            }
            model = found.get();
            resolved = latest ? registry.latestVersion(modelId) : registry.findVersion(modelId, version);
        } catch (DataAccessException e) {
            log.warn("inference failed requestId={} reason=REGISTRY_UNAVAILABLE {}", requestId, e.getMessage());
            return rejected(503, "REGISTRY_UNAVAILABLE", "The model registry could not be read.", requestId);
        }
        if (!capability.equals("AUTO") && !capability.equals(model.capability())) {
            return rejected(422, "CAPABILITY_MISMATCH", "Model '" + modelId + "' has capability "
                    + model.capability() + ", not " + capability + ".", requestId);
        }
        if (resolved.isEmpty()) {
            return rejected(404, "VERSION_NOT_FOUND", latest
                    ? "Model '" + modelId + "' has no registered versions."
                    : "Model '" + modelId + "' has no version '" + version + "'.", requestId);
        }
        ModelVersion v = resolved.get();
        if (!model.capability().equals(workers.capability())) {
            return rejected(422, "NO_ELIGIBLE_WORKER", "No worker serves capability " + model.capability()
                    + " (model '" + modelId + "').", requestId);
        }
        String target = v.artifact() + "@" + v.artifactRevision();
        if (failover.enabled()) return failoverCall(request.input(), new Target(modelId, v), trace, started);
        if (workers.healthAware()) return healthyCall(request.input(), new Target(modelId, v), trace, started);
        List<WorkerPool.Assessment> assessed = workers.assess(v.artifact(), v.artifactRevision());
        List<WorkerClient> eligible = new ArrayList<>();
        List<RoutingDecision.Candidate> candidates = new ArrayList<>();
        for (WorkerPool.Assessment a : assessed) {
            String served = a.served() == null ? null : a.served().artifact() + "@" + a.served().revision();
            String note = a.eligible() ? RoutingDecision.ELIGIBLE
                    : a.served() == null ? RoutingDecision.IDENTITY_UNKNOWN : RoutingDecision.SERVES_OTHER;
            candidates.add(new RoutingDecision.Candidate(a.worker().endpoint(), a.eligible(), note, served));
            if (a.eligible()) {
                eligible.add(a.worker());
            }
        }
        if (eligible.isEmpty()) {
            RoutingDecision none = new RoutingDecision(RoutingDecision.VERSION_AWARE, target, workers.size(), 0,
                    candidates, null, "NO_ELIGIBLE_WORKER: no worker reports " + target)
                    .withSelection(workers.policy(), workers.counts());
            return new Routed(error(422, "NO_ELIGIBLE_WORKER", "No worker currently serves model '" + modelId
                    + "' version '" + v.version() + "' (" + v.artifact() + " at revision " + v.artifactRevision()
                    + ").", requestId), none);
        }
        try (WorkerPool.Reservation reservation = workers.reserve(eligible, v.artifact(), v.artifactRevision())) {
            WorkerClient worker = reservation.worker();
            RoutingDecision decision = new RoutingDecision(RoutingDecision.VERSION_AWARE, target, workers.size(),
                    eligible.size(), candidates, worker.endpoint(), "model '" + modelId + "' version '" + v.version()
                            + "': " + workers.policyDescription() + " over " + eligible.size() + " eligible of " + workers.size() + " worker(s)").withSelection(workers.policy(), reservation.before());
            return new Routed(call(worker, request.input(), new Target(modelId, v), trace, started, decision), decision);
        }
    }

    private Routed healthyCall(String input, Target target, RequestTracker.Trace trace, long started) {
        var selected = workers.reserveHealthy(target == null ? null : target.version().artifact(),
                target == null ? null : target.version().artifactRevision());
        if (selected.error() != null) {
            boolean unservable = selected.error().equals("NO_ELIGIBLE_WORKER");
            String message = unservable
                    ? "No worker serves the requested capability or model version: every worker's current health"
                            + " reports something else."
                    : "No live worker can serve this request right now: worker health is failing or not yet observed.";
            return new Routed(error(unservable ? 422 : 503, selected.error(), message, trace.requestId()),
                    selected.decision());
        }
        try (var reservation = selected.reservation()) {
            return new Routed(call(reservation.worker(), input, target, trace, started, selected.decision()), selected.decision());
        }
    }

    /** Calls one worker and maps the outcome. target == null for requests that named no model. */
    private ResponseEntity<?> call(WorkerClient worker, String input, Target target, RequestTracker.Trace trace,
            long started, RoutingDecision decision) {
        return call(worker, input, target, trace, started, decision, null, null);
    }

    private static final class AttemptState {
        FailoverFailure failure;
    }

    private ResponseEntity<?> call(WorkerClient worker, String input, Target target, RequestTracker.Trace trace,
            long started, RoutingDecision decision, DispatchBudget budget, AttemptState state) {
        String requestId = trace.requestId();
        tracker.dispatched(trace, worker.endpoint(),
                target == null ? null : target.version().version(),
                target == null ? null : target.version().artifact(),
                target == null ? null : target.version().artifactRevision(),
                decision.toJson(MAX_DECISION_JSON));
        try {
            long predictStarted = System.nanoTime();
            WorkerPrediction p = budget == null ? worker.predict(input) : worker.predict(input, budget);
            long predictNanos = System.nanoTime() - predictStarted;
            if (target != null && !(target.version().artifact().equals(p.model())
                    && target.version().artifactRevision().equals(p.version()))) {
                worker.forgetServedModel();
                workers.predictionMismatch(worker);
                log.warn("inference failed requestId={} routedTo={} reason=WORKER_MODEL_MISMATCH expected={}@{} got={}@{}",
                        requestId, worker.endpoint(), target.version().artifact(),
                        target.version().artifactRevision(), p.model(), p.version());
                return error(502, "WORKER_MODEL_MISMATCH", "The model worker at " + worker.endpoint()
                        + " answered with a different model or revision than requested.", requestId);
            }
            workers.predictionSucceeded(worker, predictNanos);
            long totalMs = (System.nanoTime() - started) / 1_000_000;
            log.info("inference ok requestId={} routedTo={} worker={} model={} version={} label={} totalMs={}",
                    requestId, worker.endpoint(), p.worker(), target == null ? "-" : target.modelId(),
                    target == null ? "-" : target.version().version(), p.label(), totalMs);
            return ResponseEntity.ok(new InferenceResponse("success", requestId, workers.capability(),
                    p.worker(), worker.endpoint(), p.model(), p.version(), p.task(),
                    new Prediction(p.label(), p.score()), p.inferenceMs(), totalMs,
                    target == null ? null : target.modelId(), target == null ? null : target.version().version(),
                    decision));
        } catch (WorkerUnavailableException e) {
            if (state != null) state.failure = FailoverFailure.classify(e);
            if (!(e instanceof BudgetExpiredException)) workers.predictionFailed(worker, true, false);
            log.warn("inference failed requestId={} routedTo={} reason=WORKER_UNAVAILABLE {}", requestId,
                    worker.endpoint(), e.getMessage());
            return error(503, "WORKER_UNAVAILABLE", "The model worker at " + worker.endpoint()
                    + " is not reachable. Please try again shortly.", requestId);
        } catch (WorkerErrorException e) {
            if (state != null) state.failure = FailoverFailure.classify(e);
            workers.predictionFailed(worker, false, e.getCause() instanceof org.springframework.web.client.RestClientResponseException response
                    && response.getStatusCode().is5xxServerError());
            log.warn("inference failed requestId={} routedTo={} reason=WORKER_ERROR {}", requestId,
                    worker.endpoint(), e.getMessage());
            return error(502, "WORKER_ERROR", "The model worker at " + worker.endpoint() + " returned an error.",
                    requestId);
        }
    }

    private Routed failoverCall(String input, Target target, RequestTracker.Trace trace, long started) {
        DispatchBudget budget = new DispatchBudget(failover.budgetMs());
        var attempts = new ArrayList<RoutingDecision.Attempt>();
        var excluded = new java.util.HashSet<String>();
        RoutingDecision finalDecision = RoutingDecision.notRouted(workers.size(), "No attempt dispatched")
                .withSelection(workers.policy(), java.util.Map.of());
        ResponseEntity<?> response = error(503, "WORKER_UNAVAILABLE", "No worker attempt was dispatched.", trace.requestId());
        String terminal = "ATTEMPTS_EXHAUSTED";
        for (int index = 0; index < 2; index++) {
            if (Thread.currentThread().isInterrupted()) { terminal = "INTERRUPTED"; break; }
            if (budget.remaining().isZero()) { terminal = "BUDGET_EXHAUSTED"; break; }
            var selected = workers.reserveFailover(target == null ? null : target.version().artifact(),
                    target == null ? null : target.version().artifactRevision(), excluded);
            if (selected.reservation() == null) {
                terminal = attempts.isEmpty() ? selected.error() : "NO_ALTERNATE";
                if (attempts.isEmpty()) {
                    finalDecision = selected.decision();
                    response = error("NO_ELIGIBLE_WORKER".equals(selected.error()) ? 422 : 503,
                            selected.error(), "No fresh healthy eligible worker is available.", trace.requestId());
                }
                break;
            }
            var state = new AttemptState();
            long attemptStarted = System.nanoTime();
            try (var reservation = selected.reservation()) {
                finalDecision = selected.decision();
                if (Thread.currentThread().isInterrupted()) { terminal = "INTERRUPTED"; break; }
                if (budget.remaining().isZero()) { terminal = "BUDGET_EXHAUSTED"; break; }
                excluded.add(reservation.worker().endpoint());
                response = call(reservation.worker(), input, target, trace, started, finalDecision, budget, state);
            }
            boolean succeeded = response.getStatusCode().is2xxSuccessful();
            String failureClass = succeeded ? null : state.failure == null ? "MODEL_MISMATCH" : state.failure.name();
            attempts.add(new RoutingDecision.Attempt(finalDecision.chosen(), succeeded ? "SUCCEEDED" : "FAILED",
                    (System.nanoTime() - attemptStarted) / 1_000_000L, failureClass));
            log.info("worker attempt requestId={} endpoint={} outcome={} failureClass={}", trace.requestId(),
                    finalDecision.chosen(), succeeded ? "SUCCEEDED" : "FAILED", failureClass);
            if (succeeded) { terminal = "SUCCEEDED"; break; }
            if (state.failure == FailoverFailure.INTERRUPTED) { terminal = "INTERRUPTED"; break; }
            if (state.failure == FailoverFailure.BUDGET_EXPIRED) { terminal = "BUDGET_EXHAUSTED"; break; }
            if (state.failure == null || !state.failure.retryable()) { terminal = "NOT_RETRYABLE"; break; }
        }
        finalDecision = finalDecision.withAttempts(attempts, terminal);
        if (attempts.isEmpty() && (terminal.equals("BUDGET_EXHAUSTED") || terminal.equals("INTERRUPTED"))) {
            response = error(503, terminal, "No worker attempt was dispatched: " + terminal, trace.requestId());
        }
        if (response.getBody() instanceof InferenceResponse r) {
            response = ResponseEntity.status(response.getStatusCode()).body(new InferenceResponse(r.status(), r.requestId(),
                    r.capability(), r.worker(), r.workerEndpoint(), r.model(), r.version(), r.task(), r.prediction(),
                    r.workerInferenceMs(), r.totalLatencyMs(), r.modelId(), r.modelVersion(), finalDecision));
        } else if (response.getBody() instanceof ErrorResponse r) {
            response = ResponseEntity.status(response.getStatusCode()).body(new ErrorResponse(r.status(), r.error(),
                    r.message(), r.requestId(), finalDecision));
        }
        return new Routed(response, finalDecision);
    }

    /** An error returned before any worker was considered. */
    private Routed rejected(int status, String code, String message, String requestId) {
        return new Routed(error(status, code, message, requestId), RoutingDecision.notRouted(workers.size(), code).withSelection(workers.policy(), java.util.Map.of()));
    }

    private static ResponseEntity<?> error(int status, String code, String message, String requestId) {
        return ResponseEntity.status(status).body(ErrorResponse.of(code, message, requestId));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
