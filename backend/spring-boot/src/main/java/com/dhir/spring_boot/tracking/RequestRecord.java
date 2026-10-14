package com.dhir.spring_boot.tracking;

/**
 * One tracked inference request as returned by GET /api/requests/{requestId} (Sprint 2).
 * Timestamps are ISO-8601 UTC instants; fields that do not apply (yet) are null.
 * The input text is never stored or returned, only its length (inputChars).
 */
public record RequestRecord(
        String requestId,
        String status,            // RECEIVED | DISPATCHED | SUCCEEDED | FAILED
        String capability,
        String requestedModel,
        String requestedVersion,
        Integer inputChars,
        String resolvedVersion,
        String artifact,
        String artifactRevision,
        String workerEndpoint,
        String workerId,
        Integer httpStatus,
        String errorCode,
        Long totalMs,
        String receivedAt,
        String dispatchedAt,
        String completedAt) {
}
