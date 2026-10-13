package com.dhir.spring_boot.registry;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.dhir.spring_boot.registry.RegistryDtos.CreateModelRequest;
import com.dhir.spring_boot.registry.RegistryDtos.CreateVersionRequest;
import com.dhir.spring_boot.registry.RegistryDtos.ModelDetail;
import com.dhir.spring_boot.registry.RegistryDtos.ModelSummary;
import com.dhir.spring_boot.registry.RegistryDtos.ModelVersion;

/**
 * Plain SQL access to the registry tables (see db/migration/V1__registry.sql).
 *
 * Uniqueness is enforced by the database (primary key and UNIQUE constraints), not by
 * "check, then insert" in Java, so two simultaneous identical requests cannot both succeed:
 * the database rejects the second one and we translate that into a Conflict (HTTP 409).
 */
@Repository
public class RegistryRepository {

    /** "Latest" = most recently registered: newest created_at, ties broken by the higher id. */
    static final String LATEST_ORDER = "ORDER BY created_at DESC, id DESC";

    private final JdbcClient jdbc;

    public RegistryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Thrown when a unique constraint rejects an insert. code is the API error code. */
    public static final class ConflictException extends RuntimeException {
        final String code;

        ConflictException(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    /** Thrown when a version is inserted for a model that does not exist (foreign key). */
    public static final class ModelMissingException extends RuntimeException {
        ModelMissingException(String message) {
            super(message);
        }
    }

    // ---- models ----

    public ModelDetail insertModel(CreateModelRequest m) {
        try {
            jdbc.sql("""
                    INSERT INTO models (id, name, task, capability, description)
                    VALUES (:id, :name, :task, :capability, :description)""")
                    .param("id", m.id())
                    .param("name", m.name())
                    .param("task", m.task())
                    .param("capability", m.capability())
                    .param("description", m.description())
                    .update();
        } catch (DuplicateKeyException e) {
            throw new ConflictException("MODEL_ALREADY_EXISTS", "A model with id '" + m.id() + "' already exists.");
        }
        return findModel(m.id()).orElseThrow();
    }

    public boolean modelExists(String id) {
        return jdbc.sql("SELECT COUNT(*) FROM models WHERE id = :id").param("id", id)
                .query(Long.class).single() > 0;
    }

    public Optional<ModelDetail> findModel(String id) {
        Optional<ModelDetail> base = jdbc.sql("""
                SELECT id, name, task, capability, description, created_at,
                       (SELECT COUNT(*) FROM model_versions v WHERE v.model_id = m.id) AS version_count
                FROM models m WHERE id = :id""")
                .param("id", id)
                .query((rs, n) -> new ModelDetail(rs.getString("id"), rs.getString("name"), rs.getString("task"),
                        rs.getString("capability"), rs.getString("description"), instant(rs),
                        rs.getInt("version_count"), null))
                .optional();
        return base.map(m -> new ModelDetail(m.id(), m.name(), m.task(), m.capability(), m.description(),
                m.createdAt(), m.versionCount(), latestVersion(m.id()).orElse(null)));
    }

    /** All models, oldest registration first (created_at, then id). */
    public List<ModelSummary> listModels() {
        return jdbc.sql("""
                SELECT m.id, m.name, m.task, m.capability, m.description, m.created_at,
                       (SELECT v.version FROM model_versions v WHERE v.model_id = m.id
                        ORDER BY v.created_at DESC, v.id DESC FETCH FIRST 1 ROW ONLY) AS latest_version
                FROM models m
                ORDER BY m.created_at, m.id""")
                .query((rs, n) -> new ModelSummary(rs.getString("id"), rs.getString("name"),
                        rs.getString("capability"), rs.getString("task"), rs.getString("latest_version"), null,
                        "REGISTERED", rs.getString("description"), instant(rs)))
                .list();
    }

    // ---- versions ----

    public ModelVersion insertVersion(String modelId, CreateVersionRequest v) {
        try {
            jdbc.sql("""
                    INSERT INTO model_versions (model_id, version, artifact, artifact_revision, framework, notes)
                    VALUES (:modelId, :version, :artifact, :revision, :framework, :notes)""")
                    .param("modelId", modelId)
                    .param("version", v.version())
                    .param("artifact", v.artifact())
                    .param("revision", v.artifactRevision())
                    .param("framework", v.framework())
                    .param("notes", v.notes())
                    .update();
        } catch (DuplicateKeyException e) {
            // Which constraint fired? Ask the database rather than parsing vendor-specific error text.
            if (findVersion(modelId, v.version()).isPresent()) {
                throw new ConflictException("VERSION_ALREADY_EXISTS",
                        "Model '" + modelId + "' already has a version '" + v.version() + "'.");
            }
            throw new ConflictException("DUPLICATE_ARTIFACT", "Artifact '" + v.artifact() + "' at revision "
                    + v.artifactRevision() + " is already registered for model '" + modelId
                    + "' under another version label.");
        } catch (DataIntegrityViolationException e) {
            // Foreign key: the model does not exist.
            throw new ModelMissingException("Model '" + modelId + "' does not exist.");
        }
        return findVersion(modelId, v.version()).orElseThrow();
    }

    /** Versions of one model, oldest registration first. */
    public List<ModelVersion> listVersions(String modelId) {
        return jdbc.sql("SELECT * FROM model_versions WHERE model_id = :modelId ORDER BY created_at, id")
                .param("modelId", modelId)
                .query((rs, n) -> version(rs))
                .list();
    }

    public Optional<ModelVersion> findVersion(String modelId, String version) {
        return jdbc.sql("SELECT * FROM model_versions WHERE model_id = :modelId AND version = :version")
                .param("modelId", modelId)
                .param("version", version)
                .query((rs, n) -> version(rs))
                .optional();
    }

    public Optional<ModelVersion> latestVersion(String modelId) {
        return jdbc.sql("SELECT * FROM model_versions WHERE model_id = :modelId " + LATEST_ORDER
                + " FETCH FIRST 1 ROW ONLY")
                .param("modelId", modelId)
                .query((rs, n) -> version(rs))
                .optional();
    }

    private static ModelVersion version(ResultSet rs) throws SQLException {
        return new ModelVersion(rs.getLong("id"), rs.getString("model_id"), rs.getString("version"),
                rs.getString("artifact"), rs.getString("artifact_revision"), rs.getString("framework"),
                rs.getString("notes"), instant(rs));
    }

    /** created_at as an ISO-8601 UTC instant string, e.g. 2026-10-13T11:02:03.123456Z. */
    private static String instant(ResultSet rs) throws SQLException {
        OffsetDateTime t = rs.getObject("created_at", OffsetDateTime.class);
        return t == null ? null : t.toInstant().toString();
    }
}
