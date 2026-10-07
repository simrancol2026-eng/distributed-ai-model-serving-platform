package com.dhir.spring_boot.inference;

/**
 * Data shapes for the unified inference API, kept in one file so the whole
 * contract can be read at a glance. Records serialize to/from JSON automatically.
 */
public final class InferenceDtos {

    private InferenceDtos() {
    }

    /** Body of POST /api/inference, e.g. {"capability": "TEXT", "input": "I love this"}. */
    public record InferenceRequest(String capability, String input) {
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
            String worker,
            String model,
            String version,
            String task,
            Prediction prediction,
            double workerInferenceMs,
            long totalLatencyMs) {
    }

    /** Uniform error body: status is always "error"; error is a stable machine-readable code. */
    public record ErrorResponse(String status, String error, String message, String requestId) {
        public static ErrorResponse of(String error, String message, String requestId) {
            return new ErrorResponse("error", error, message, requestId);
        }
    }
}
