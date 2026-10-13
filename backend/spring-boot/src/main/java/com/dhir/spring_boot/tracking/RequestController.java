package com.dhir.spring_boot.tracking;

import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dhir.spring_boot.inference.InferenceDtos.ErrorResponse;

/**
 * Read API for tracked inference requests (Sprint 2).
 *
 *   GET /api/requests/{requestId}   one request             200 | 400 INVALID_REQUEST | 404 REQUEST_NOT_FOUND
 *   GET /api/requests?limit=N       newest first, N 1..100  200 | 400 INVALID_REQUEST
 *   Database unreadable (either endpoint)                   503 TRACKING_UNAVAILABLE
 */
@RestController
public class RequestController {

    static final int DEFAULT_LIMIT = 20;
    private static final Pattern UUID_TEXT =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final Logger log = LoggerFactory.getLogger(RequestController.class);

    private final RequestTracker tracker;

    public RequestController(RequestTracker tracker) {
        this.tracker = tracker;
    }

    @GetMapping("/api/requests/{requestId}")
    public ResponseEntity<?> get(@PathVariable String requestId) {
        if (!UUID_TEXT.matcher(requestId).matches()) {
            return error(400, "INVALID_REQUEST", "requestId must be a lowercase UUID as returned by /api/inference.");
        }
        try {
            return tracker.find(requestId)
                    .<ResponseEntity<?>>map(ResponseEntity::ok)
                    .orElseGet(() -> error(404, "REQUEST_NOT_FOUND", "No tracked request '" + requestId + "'."));
        } catch (DataAccessException e) {
            return unavailable(e);
        }
    }

    @GetMapping("/api/requests")
    public ResponseEntity<?> list(@RequestParam(required = false) String limit) {
        int n = DEFAULT_LIMIT;
        if (limit != null) {
            try {
                n = Integer.parseInt(limit.trim());
            } catch (NumberFormatException e) {
                n = -1;
            }
            if (n < 1 || n > RequestTracker.MAX_LIST) {
                return error(400, "INVALID_REQUEST", "limit must be a whole number from 1 to "
                        + RequestTracker.MAX_LIST + ".");
            }
        }
        try {
            return ResponseEntity.ok(tracker.recent(n));
        } catch (DataAccessException e) {
            return unavailable(e);
        }
    }

    private ResponseEntity<?> unavailable(DataAccessException e) {
        log.warn("request tracking read failed reason={}", e.getMessage());
        return error(503, "TRACKING_UNAVAILABLE", "The request tracking store could not be read.");
    }

    private static ResponseEntity<?> error(int status, String code, String message) {
        return ResponseEntity.status(status).body(ErrorResponse.of(code, message, UUID.randomUUID().toString()));
    }
}
