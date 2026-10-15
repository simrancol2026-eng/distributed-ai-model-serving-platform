package com.dhir.spring_boot.inference;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class HealthEvidenceTest {
    final JsonMapper mapper = JsonMapper.builder().build();

    @Test void apiAndStoredDecisionHaveIdenticalHealthFields() {
        var health = new HealthRouting.Evidence(true, 4L, 12.5, 3, 25L, 12.5, 25.0, 0);
        var candidate = new RoutingDecision.Candidate("localhost:8001", true, "ELIGIBLE", "model@rev", 1, health);
        var decision = new RoutingDecision("VERSION_AWARE", "model@rev", 1, 1,
                List.of(candidate), "localhost:8001", "minimum score", "HEALTH_LATENCY");
        assertEquals(mapper.readTree(mapper.writeValueAsString(decision)), mapper.readTree(decision.toJson(8000)));
        assertTrue(decision.toLogString().contains("score=25.0"));
    }

    @Test void baselineApiAndStoredDecisionBothOmitHealth() {
        var decision = RoutingDecisionTest.sample();
        assertEquals(mapper.readTree(mapper.writeValueAsString(decision)), mapper.readTree(decision.toJson(8000)));
        assertFalse(mapper.writeValueAsString(decision).contains("health"));
    }

    @Test void largeHealthDecisionsOmitCandidatesWithoutInvalidJson() {
        var candidates = new ArrayList<RoutingDecision.Candidate>();
        for (int i = 0; i < 100; i++) candidates.add(new RoutingDecision.Candidate("localhost:" + (8000 + i),
                true, "ELIGIBLE", "model@rev", 0,
                new HealthRouting.Evidence(true, 1L, null, 0, null, 100.0, 100.0, 0)));
        var d = new RoutingDecision("VERSION_AWARE", "model@rev", 100, 100, candidates,
                "localhost:8000", "minimum score", "HEALTH_LATENCY");
        String stored = d.toJson(8000);
        assertTrue(stored.length() <= 8000);
        assertEquals(100, mapper.readTree(stored).get("candidatesOmitted").asInt());
        assertEquals(0, mapper.readTree(stored).get("candidates").size());
    }
}
