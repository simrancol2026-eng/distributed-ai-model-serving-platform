package com.dhir.spring_boot.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.dhir.spring_boot.inference.RoutingDecision.Candidate;

/** RoutingDecision formatting (Sprint 2 #5): plain Java, no Spring. */
class RoutingDecisionTest {

    static RoutingDecision sample() {
        return new RoutingDecision(RoutingDecision.VERSION_AWARE, "art@r1", 3, 1, List.of(
                new Candidate("127.0.0.1:8001", true, RoutingDecision.ELIGIBLE, "art@r1"),
                new Candidate("127.0.0.1:8002", false, RoutingDecision.SERVES_OTHER, "art@r2"),
                new Candidate("127.0.0.1:8003", false, RoutingDecision.IDENTITY_UNKNOWN, null)),
                "127.0.0.1:8001", "round-robin over 1 eligible of 3 worker(s)");
    }

    @Test
    void jsonHasEveryFieldInAFixedOrder() {
        assertEquals("{\"strategy\":\"VERSION_AWARE\",\"target\":\"art@r1\",\"poolSize\":3,\"eligibleCount\":1,"
                + "\"candidates\":["
                + "{\"endpoint\":\"127.0.0.1:8001\",\"eligible\":true,\"note\":\"ELIGIBLE\",\"served\":\"art@r1\"},"
                + "{\"endpoint\":\"127.0.0.1:8002\",\"eligible\":false,\"note\":\"SERVES_OTHER\",\"served\":\"art@r2\"},"
                + "{\"endpoint\":\"127.0.0.1:8003\",\"eligible\":false,\"note\":\"IDENTITY_UNKNOWN\",\"served\":null}],"
                + "\"chosen\":\"127.0.0.1:8001\",\"reason\":\"round-robin over 1 eligible of 3 worker(s)\"}",
                sample().toJson(8000));
    }

    @Test
    void jsonEscapesQuotesBackslashesAndControlCharacters() {
        RoutingDecision d = new RoutingDecision(RoutingDecision.VERSION_AWARE, "a\"b\\c", 0, 0, List.of(), null,
                "line1\nline2\t\u0001");
        String json = d.toJson(8000);
        assertTrue(json.contains("\"target\":\"a\\\"b\\\\c\""), json);
        assertTrue(json.contains("\"reason\":\"line1\\nline2\\t\\u0001\""), json);
        assertTrue(json.contains("\"chosen\":null"), json);
    }

    @Test
    void tooLongJsonDropsCandidatesButStaysComplete() {
        List<Candidate> many = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            many.add(new Candidate("worker-host-number-" + i + ".example:8000", false, RoutingDecision.IDENTITY_UNKNOWN,
                    null));
        }
        RoutingDecision d = new RoutingDecision(RoutingDecision.VERSION_AWARE, "art@r1", 200, 0, many, null, "none");
        String json = d.toJson(8000);
        assertTrue(json.length() <= 8000, "length " + json.length());
        assertTrue(json.contains("\"candidates\":[],\"candidatesOmitted\":200"), json);
        assertTrue(json.startsWith("{") && json.endsWith("}"), json);
    }

    @Test
    void notRoutedHasNoCandidatesAndNoChoice() {
        String json = RoutingDecision.notRouted(2, "INVALID_REQUEST").toJson(8000);
        assertEquals("{\"strategy\":\"NOT_ROUTED\",\"target\":null,\"poolSize\":2,\"eligibleCount\":0,"
                + "\"candidates\":[],\"chosen\":null,\"reason\":\"INVALID_REQUEST\"}", json);
    }

    @Test
    void logLineNamesEachCandidateAndWhyItWasSkipped() {
        String line = sample().toLogString();
        assertEquals("strategy=VERSION_AWARE target=art@r1 eligible=1/3 candidates=[127.0.0.1:8001:ELIGIBLE, "
                + "127.0.0.1:8002:SERVES_OTHER(art@r2), 127.0.0.1:8003:IDENTITY_UNKNOWN] chosen=127.0.0.1:8001 "
                + "reason=\"round-robin over 1 eligible of 3 worker(s)\"", line);
        assertFalse(line.contains("input"));
    }
}
