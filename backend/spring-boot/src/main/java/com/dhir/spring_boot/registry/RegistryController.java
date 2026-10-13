package com.dhir.spring_boot.registry;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.dhir.spring_boot.inference.InferenceDtos.ErrorResponse;
import com.dhir.spring_boot.registry.RegistryDtos.CreateModelRequest;
import com.dhir.spring_boot.registry.RegistryDtos.CreateVersionRequest;
import com.dhir.spring_boot.registry.RegistryDtos.ModelDetail;
import com.dhir.spring_boot.registry.RegistryDtos.ModelSummary;
import com.dhir.spring_boot.registry.RegistryDtos.ModelVersion;
import com.dhir.spring_boot.registry.RegistryRepository.ConflictException;
import com.dhir.spring_boot.registry.RegistryRepository.ModelMissingException;
import com.dhir.spring_boot.registry.RegistryValidation.InvalidRequestException;

/**
 * Model registry API (Sprint 2). Records which models and versions exist; it does not start,
 * load or route to them, and registering a version does not mean it is deployed.
 *
 *   POST /api/models                              201 | 400 INVALID_REQUEST | 409 MODEL_ALREADY_EXISTS
 *   GET  /api/models                              200 (array, may be empty)
 *   GET  /api/models/{id}                         200 | 404 MODEL_NOT_FOUND
 *   POST /api/models/{id}/versions                201 | 400 | 404 MODEL_NOT_FOUND
 *                                                     | 409 VERSION_ALREADY_EXISTS | 409 DUPLICATE_ARTIFACT
 *   GET  /api/models/{id}/versions                200 | 404 MODEL_NOT_FOUND
 *   GET  /api/models/{id}/versions/{version}      200 | 404 MODEL_NOT_FOUND | 404 VERSION_NOT_FOUND
 */
@RestController
public class RegistryController {

    private static final Logger log = LoggerFactory.getLogger(RegistryController.class);

    private final RegistryRepository repo;

    public RegistryController(RegistryRepository repo) {
        this.repo = repo;
    }

    @PostMapping("/api/models")
    public ResponseEntity<ModelDetail> createModel(@RequestBody(required = false) CreateModelRequest body) {
        CreateModelRequest m = RegistryValidation.model(body);
        ModelDetail created = repo.insertModel(m);
        log.info("registry model created id={}", created.id());
        return ResponseEntity.created(URI.create("/api/models/" + created.id())).body(created);
    }

    @GetMapping("/api/models")
    public List<ModelSummary> listModels() {
        return repo.listModels();
    }

    @GetMapping("/api/models/{id}")
    public ResponseEntity<?> getModel(@PathVariable String id) {
        return repo.findModel(id).<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> modelNotFound(id));
    }

    @PostMapping("/api/models/{id}/versions")
    public ResponseEntity<?> createVersion(@PathVariable String id,
            @RequestBody(required = false) CreateVersionRequest body) {
        CreateVersionRequest v = RegistryValidation.version(body);
        if (!repo.modelExists(id)) {
            return modelNotFound(id);
        }
        ModelVersion created = repo.insertVersion(id, v);
        log.info("registry version created model={} version={} revision={}", id, created.version(),
                created.artifactRevision());
        return ResponseEntity.created(URI.create("/api/models/" + id + "/versions/" + created.version()))
                .body(created);
    }

    @GetMapping("/api/models/{id}/versions")
    public ResponseEntity<?> listVersions(@PathVariable String id) {
        if (!repo.modelExists(id)) {
            return modelNotFound(id);
        }
        return ResponseEntity.ok(repo.listVersions(id));
    }

    @GetMapping("/api/models/{id}/versions/{version}")
    public ResponseEntity<?> getVersion(@PathVariable String id, @PathVariable String version) {
        if (!repo.modelExists(id)) {
            return modelNotFound(id);
        }
        return repo.findVersion(id, version).<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404).body(ErrorResponse.of("VERSION_NOT_FOUND",
                        "Model '" + id + "' has no version '" + version + "'.", newRequestId())));
    }

    // ---- errors (only for this controller; the inference API keeps its own handling) ----

    @ExceptionHandler(InvalidRequestException.class)
    ResponseEntity<ErrorResponse> invalid(InvalidRequestException e) {
        return ResponseEntity.badRequest().body(ErrorResponse.of("INVALID_REQUEST", e.getMessage(), newRequestId()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(ErrorResponse.of("INVALID_REQUEST",
                "Request body must be a JSON object with the documented fields.", newRequestId()));
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<ErrorResponse> conflict(ConflictException e) {
        log.info("registry conflict {}: {}", e.code, e.getMessage());
        return ResponseEntity.status(409).body(ErrorResponse.of(e.code, e.getMessage(), newRequestId()));
    }

    @ExceptionHandler(ModelMissingException.class)
    ResponseEntity<ErrorResponse> missing(ModelMissingException e) {
        return ResponseEntity.status(404).body(ErrorResponse.of("MODEL_NOT_FOUND", e.getMessage(), newRequestId()));
    }

    private static ResponseEntity<?> modelNotFound(String id) {
        return ResponseEntity.status(404)
                .body(ErrorResponse.of("MODEL_NOT_FOUND", "No model with id '" + id + "'.", newRequestId()));
    }

    private static String newRequestId() {
        return UUID.randomUUID().toString();
    }
}
