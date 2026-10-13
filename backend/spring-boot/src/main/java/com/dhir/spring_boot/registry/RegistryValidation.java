package com.dhir.spring_boot.registry;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import com.dhir.spring_boot.registry.RegistryDtos.CreateModelRequest;
import com.dhir.spring_boot.registry.RegistryDtos.CreateVersionRequest;

/**
 * Input rules for the registry. Every string is trimmed first; then it is checked.
 * Invalid input throws InvalidRequestException, which the controller turns into 400 INVALID_REQUEST.
 */
final class RegistryValidation {

    /** Model id: lowercase slug, 2-63 chars, safe in a URL path. */
    static final Pattern MODEL_ID = Pattern.compile("^[a-z0-9][a-z0-9-]{1,62}$");

    /** Version label: 1-64 chars of letters, digits, '.', '_' or '-'; starts with a letter/digit; no '/'. */
    static final Pattern VERSION_LABEL = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");

    /** Artifact id, e.g. "distilbert-base-uncased-finetuned-sst-2-english" or "org/model". */
    static final Pattern ARTIFACT = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._/-]{0,199}$");

    /**
     * Full 40-hex git commit (Hugging Face revision). Abbreviated hashes are rejected because they are
     * not guaranteed unique. Registering a revision records metadata only; it does not prove the
     * artifact was downloaded or ever executed.
     */
    static final Pattern REVISION = Pattern.compile("^[0-9a-f]{40}$");

    static final Set<String> CAPABILITIES = Set.of("TEXT", "CODE", "IMAGE", "DOCUMENT");

    private RegistryValidation() {
    }

    static final class InvalidRequestException extends RuntimeException {
        InvalidRequestException(String message) {
            super(message);
        }
    }

    static CreateModelRequest model(CreateModelRequest r) {
        if (r == null) {
            throw new InvalidRequestException("Request body is required.");
        }
        String id = required("id", r.id(), 63);
        if (!MODEL_ID.matcher(id).matches()) {
            throw new InvalidRequestException(
                    "Field 'id' must be 2-63 characters: lowercase letters, digits and '-', starting with a letter or digit.");
        }
        String name = required("name", r.name(), 200);
        String task = required("task", r.task(), 100);
        String capability = required("capability", r.capability(), 16).toUpperCase(Locale.ROOT);
        if (!CAPABILITIES.contains(capability)) {
            throw new InvalidRequestException("Field 'capability' must be one of TEXT, CODE, IMAGE, DOCUMENT.");
        }
        String description = optional("description", r.description(), 1000);
        return new CreateModelRequest(id, name, task, capability, description);
    }

    static CreateVersionRequest version(CreateVersionRequest r) {
        if (r == null) {
            throw new InvalidRequestException("Request body is required.");
        }
        String version = required("version", r.version(), 64);
        if (!isVersionLabel(version)) {
            throw new InvalidRequestException(
                    "Field 'version' must be 1-64 characters: letters, digits, '.', '_' or '-', starting with a letter or digit.");
        }
        String artifact = required("artifact", r.artifact(), 200);
        if (!ARTIFACT.matcher(artifact).matches()) {
            throw new InvalidRequestException(
                    "Field 'artifact' must be 1-200 characters: letters, digits, '.', '_', '-' or '/'.");
        }
        String revision = required("artifactRevision", r.artifactRevision(), 100).toLowerCase(Locale.ROOT);
        if (!REVISION.matcher(revision).matches()) {
            throw new InvalidRequestException(
                    "Field 'artifactRevision' must be the full 40-character hexadecimal commit hash (abbreviated hashes are not accepted).");
        }
        String framework = optional("framework", r.framework(), 100);
        String notes = optional("notes", r.notes(), 1000);
        return new CreateVersionRequest(version, artifact, revision, framework, notes);
    }

    static boolean isModelId(String s) {
        return s != null && MODEL_ID.matcher(s).matches();
    }

    static boolean isVersionLabel(String s) {
        return s != null && VERSION_LABEL.matcher(s).matches();
    }

    private static String required(String field, String value, int max) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty()) {
            throw new InvalidRequestException("Field '" + field + "' is required and must not be blank.");
        }
        if (v.length() > max) {
            throw new InvalidRequestException("Field '" + field + "' must be at most " + max + " characters.");
        }
        return v;
    }

    private static String optional(String field, String value, int max) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String v = value.trim();
        if (v.length() > max) {
            throw new InvalidRequestException("Field '" + field + "' must be at most " + max + " characters.");
        }
        return v;
    }
}
