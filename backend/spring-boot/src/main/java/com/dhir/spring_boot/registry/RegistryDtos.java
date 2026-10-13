package com.dhir.spring_boot.registry;

/**
 * JSON shapes of the model registry API, kept in one file so the contract can be read at a glance.
 * Errors reuse the inference error shape: {"status":"error","error":CODE,"message":...,"requestId":...}.
 */
public final class RegistryDtos {

    private RegistryDtos() {
    }

    /** Body of POST /api/models. */
    public record CreateModelRequest(String id, String name, String task, String capability, String description) {
    }

    /** Body of POST /api/models/{id}/versions. */
    public record CreateVersionRequest(String version, String artifact, String artifactRevision, String framework,
            String notes) {
    }

    /** One registered version of a model. createdAt is an ISO-8601 UTC instant. */
    public record ModelVersion(
            long id,
            String modelId,
            String version,
            String artifact,
            String artifactRevision,
            String framework,
            String notes,
            String createdAt) {
    }

    /**
     * Full model record (GET /api/models/{id}, POST /api/models).
     * latestVersion = most recently REGISTERED version (created_at, then id), or null.
     * It says nothing about which version is deployed or serving traffic.
     */
    public record ModelDetail(
            String id,
            String name,
            String task,
            String capability,
            String description,
            String createdAt,
            int versionCount,
            ModelVersion latestVersion) {
    }

    /**
     * Entry of GET /api/models. Field names match the frontend's Model type
     * (id, name, capability, task, version, workers, status).
     * version  = label of the most recently registered version, or null.
     * workers  = always null: worker assignment is not tracked by the registry yet.
     * status   = always "REGISTERED": the model is recorded, not that it is running.
     */
    public record ModelSummary(
            String id,
            String name,
            String capability,
            String task,
            String version,
            Integer workers,
            String status,
            String description,
            String createdAt) {
    }
}
