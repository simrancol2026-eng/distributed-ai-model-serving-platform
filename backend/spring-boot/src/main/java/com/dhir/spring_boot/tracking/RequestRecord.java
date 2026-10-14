package com.dhir.spring_boot.tracking;

import com.fasterxml.jackson.annotation.JsonRawValue;

/**
 * One tracked inference request as returned by GET /api/requests/{requestId} (Sprint 2).
 * Timestamps are ISO-8601 UTC instants; fields that do not apply (yet) are null.
 * The input text is never stored or returned, only its length (inputChars).
 * routing = the stored RoutingDecision JSON (Sprint 2 #5), emitted as a JSON object, or null for rows
 * written before V3 / when the routing write was skipped.
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
        String completedAt,
        @JsonRawValue String routing) {
}
