package com.dhir.spring_boot.inference;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class FailoverEvidenceTest {
    @Test void legacyDecisionsDoNotGainFailoverFields() {
        RoutingDecision d = RoutingDecision.notRouted(2, "rejected");
        assertFalse(d.toJson(8000).contains("attempts"));
        assertFalse(JsonMapper.builder().build().writeValueAsString(d).contains("attempts"));
    }

    @Test void candidateOmissionKeepsBothAttemptsAndTerminalReason() {
        var candidates = new ArrayList<RoutingDecision.Candidate>();
        for (int i = 0; i < 200; i++) {
            candidates.add(new RoutingDecision.Candidate("worker" + i, true, "ELIGIBLE", "model@revision"));
        }
        var attempts = List.of(new RoutingDecision.Attempt("worker0", "FAILED", 10, "TRANSIENT_HTTP"),
                new RoutingDecision.Attempt("worker1", "SUCCEEDED", 20, null));
        var d = new RoutingDecision("VERSION_AWARE", "model@revision", 200, 200, candidates,
                "worker1", "alternate", "ROUND_ROBIN").withAttempts(attempts, "SUCCEEDED");
        String stored = d.toJson(8000);
        assertTrue(stored.length() <= 8000);
        var json = JsonMapper.builder().build().readTree(stored);
        assertEquals(200, json.get("candidatesOmitted").asInt());
        assertEquals(2, json.get("attempts").size());
        assertEquals("worker1", json.get("attempts").get(1).get("endpoint").asText());
        assertEquals("SUCCEEDED", json.get("terminalReason").asText());
    }

    @Test void attemptLogCannotInjectLines() {
        var d = RoutingDecision.notRouted(1, "test").withAttempts(
                List.of(new RoutingDecision.Attempt("a\nb", "FAILED", 1, "UNKNOWN")), "NOT_RETRYABLE");
        assertFalse(d.toLogString().contains("\n"));
    }
}
