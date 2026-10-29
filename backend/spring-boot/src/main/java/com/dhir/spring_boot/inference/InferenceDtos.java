package com.dhir.spring_boot.inference;

/**
 * Data shapes for the unified inference API, kept in one file so the whole
 * contract can be read at a glance. Records serialize to/from JSON automatically.
 */
public final class InferenceDtos {

    private InferenceDtos() {
    }

    /**
     * Body of POST /api/inference, e.g. {"capability": "TEXT", "input": "I love this"}.
     * Optional (Sprint 2): "model" = registry model id, "version" = registry version label or "latest"
     * (default when "model" is given). Without "model" the request behaves exactly as before.
     */
    public record InferenceRequest(String capability, String input, String model, String version) {
    }

    /** What the Python worker returns from POST /predict (field names must match its JSON). */
    public record WorkerPrediction(
            String worker,
            String model,
            String version,
            String task,
            String label,
            double score,
            double inferenceMs) {
    }

    public record Prediction(String label, double score) {
    }

    /** Normalized successful response returned to the frontend. */
    public record InferenceResponse(
            String status,
            String requestId,
            String capability,
            String worker,          // id reported by the worker process itself (its WORKER_ID)
            String workerEndpoint,  // host:port the backend sent this request to (round-robin choice)
            String model,
            String version,
            String task,
            Prediction prediction,
            double workerInferenceMs,
            long totalLatencyMs,
            String modelId,       // registry model id, only when the request named a model (else null)
            String modelVersion,  // registry version label that was resolved and served (else null)
            RoutingDecision routing) { // why this worker was chosen (Sprint 2 #5, D-044)
    }

    /** Uniform error body: status is always "error"; error is a stable machine-readable code. */
    public record ErrorResponse(String status, String error, String message, String requestId,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            RoutingDecision routing) {
        public ErrorResponse(String status, String error, String message, String requestId) {
            this(status, error, message, requestId, null);
        }
        public static ErrorResponse of(String error, String message, String requestId) {
            return new ErrorResponse("error", error, message, requestId);
        }
    }
}
