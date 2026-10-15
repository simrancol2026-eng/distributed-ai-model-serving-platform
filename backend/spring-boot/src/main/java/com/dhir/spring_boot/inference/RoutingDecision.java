package com.dhir.spring_boot.inference;

import java.util.List;

/**
 * Why a request went (or did not go) to a worker (Sprint 2, #5, D-044).
 *
 * Built once per POST /api/inference by InferenceController from the SAME data the router used,
 * so the log line, the stored row and the API response all describe the real decision:
 *
 *   strategy       ROUND_ROBIN_ALL  - no "model": next worker of all configured workers
 *                  VERSION_AWARE    - "model" given: only workers whose /health reports the target
 *                  NOT_ROUTED       - rejected before any worker was considered (400/404/422/503)
 *   target         artifact@revision the request needs (VERSION_AWARE only, else null)
 *   candidates     every configured worker, with whether it was eligible and why
 *   eligibleCount  how many candidates could have served the request
 *   chosen         host:port the request was sent to (null if none)
 *   reason         one short human-readable sentence
 *
 * The input text is never part of a decision.
 */
public record RoutingDecision(
        String strategy,
        String target,
        int poolSize,
        int eligibleCount,
        List<Candidate> candidates,
        String chosen,
        String reason,
        String policy) {

    public RoutingDecision(String strategy, String target, int poolSize, int eligibleCount,
            List<Candidate> candidates, String chosen, String reason) {
        this(strategy, target, poolSize, eligibleCount, candidates, chosen, reason, "ROUND_ROBIN");
    }

    public RoutingDecision withSelection(String policy, java.util.Map<String, Integer> counts) {
        return new RoutingDecision(strategy, target, poolSize, eligibleCount,
                candidates.stream().map(c -> new Candidate(c.endpoint(), c.eligible(), c.note(), c.served(),
                        counts.get(c.endpoint()), c.health())).toList(), chosen, reason, policy);
    }

    public static final String ROUND_ROBIN_ALL = "ROUND_ROBIN_ALL";
    public static final String VERSION_AWARE = "VERSION_AWARE";
    public static final String NOT_ROUTED = "NOT_ROUTED";

    /**
     * note values for a candidate. Eligibility is about IDENTITY only (what the worker's /health says
     * it serves); it does not mean the worker is up right now, and there is no failover.
     *   ANY_WORKER       - ROUND_ROBIN_ALL: identity not checked, any configured worker may be chosen
     *   ELIGIBLE         - VERSION_AWARE: reports exactly the target artifact@revision
     *   SERVES_OTHER     - VERSION_AWARE: reports a different artifact@revision (see served)
     *   IDENTITY_UNKNOWN - VERSION_AWARE: identity never read / not reported (fail closed)
     * NOT_ROUTED decisions have no candidates at all (nothing was evaluated).
     */
    public static final String ANY_WORKER = "ANY_WORKER";
    public static final String ELIGIBLE = "ELIGIBLE";
    public static final String IDENTITY_UNKNOWN = "IDENTITY_UNKNOWN";
    public static final String SERVES_OTHER = "SERVES_OTHER";

    /**
     * One configured worker as the router saw it.
     * served = artifact@revision from the worker's /health (null if unknown or not checked).
     */
    public record Candidate(String endpoint, boolean eligible, String note, String served, Integer inFlight,
                            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                            HealthRouting.Evidence health) {
        public Candidate(String endpoint, boolean eligible, String note, String served, Integer inFlight) {
            this(endpoint, eligible, note, served, inFlight, null);
        }
        public Candidate(String endpoint, boolean eligible, String note, String served) {
            this(endpoint, eligible, note, served, null);
        }
    }

    public RoutingDecision {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    /** Rejected before routing: no worker was considered. */
    public static RoutingDecision notRouted(int poolSize, String reason) {
        return new RoutingDecision(NOT_ROUTED, null, poolSize, 0, List.of(), null, reason);
    }

    /**
     * One log line, e.g. strategy=VERSION_AWARE target=a@r1 eligible=1/2 candidates=[...] chosen=... reason=...
     * Worker-reported identities are not validated, so every text value is passed through oneLine():
     * a CR/LF or other control character can never split the line or forge another log entry.
     */
    public String toLogString() {
        StringBuilder sb = new StringBuilder();
        sb.append("strategy=").append(oneLine(strategy))
                .append(" policy=").append(oneLine(policy))
                .append(" target=").append(target == null ? "-" : oneLine(target))
                .append(" eligible=").append(eligibleCount).append('/').append(poolSize)
                .append(" candidates=[");
        for (int i = 0; i < candidates.size(); i++) {
            Candidate c = candidates.get(i);
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(oneLine(c.endpoint())).append(':').append(oneLine(c.note()));
            if (c.served() != null && !c.eligible()) {
                sb.append('(').append(oneLine(c.served())).append(')');
            }
            sb.append("{inFlight=").append(c.inFlight()).append('}');
            if (c.health() != null) sb.append("{health=").append(c.health()).append('}');
        }
        sb.append("] chosen=").append(chosen == null ? "-" : oneLine(chosen))
                .append(" reason=\"").append(oneLine(reason)).append('"');
        return sb.toString();
    }

    /** Control characters (incl. CR, LF, DEL, U+2028/9) become visible escapes, e.g. backslash-n. */
    static String oneLine(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\n') {
                sb.append("\\n");
            } else if (ch == '\r') {
                sb.append("\\r");
            } else if (ch == '\t') {
                sb.append("\\t");
            } else if (ch < 0x20 || ch == 0x7f || ch == '\u2028' || ch == '\u2029') {
                sb.append(String.format("\\u%04x", (int) ch));
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    /**
     * Compact JSON for the routing_decision column. Written by hand (a few fixed fields) so the stored
     * text does not depend on Jackson settings. If it would exceed maxChars, candidates are dropped
     * and "candidatesOmitted" says how many, so the stored value is always complete, valid JSON.
     */
    public String toJson(int maxChars) {
        String full = json(true);
        return full.length() <= maxChars ? full : json(false);
    }

    private String json(boolean withCandidates) {
        StringBuilder sb = new StringBuilder("{");
        field(sb, "strategy", strategy).append(',');
        field(sb, "policy", policy).append(',');
        field(sb, "target", target).append(',');
        sb.append("\"poolSize\":").append(poolSize).append(',');
        sb.append("\"eligibleCount\":").append(eligibleCount).append(',');
        if (withCandidates) {
            sb.append("\"candidates\":[");
            for (int i = 0; i < candidates.size(); i++) {
                Candidate c = candidates.get(i);
                if (i > 0) {
                    sb.append(',');
                }
                sb.append('{');
                field(sb, "endpoint", c.endpoint()).append(',');
                sb.append("\"eligible\":").append(c.eligible()).append(',');
                field(sb, "note", c.note()).append(',');
                field(sb, "served", c.served()).append(',');
                sb.append("\"inFlight\":").append(c.inFlight());
                if (c.health() != null) {
                    var h = c.health();
                    sb.append(",\"health\":{")
                            .append("\"live\":").append(h.live())
                            .append(",\"healthAgeMs\":").append(h.healthAgeMs())
                            .append(",\"ewmaMs\":").append(h.ewmaMs())
                            .append(",\"samples\":").append(h.samples())
                            .append(",\"sampleAgeMs\":").append(h.sampleAgeMs())
                            .append(",\"estimateMs\":").append(h.estimateMs())
                            .append(",\"score\":").append(h.score())
                            .append(",\"cooldownMs\":").append(h.cooldownMs()).append('}');
                }
                sb.append('}');
            }
            sb.append("],");
        } else {
            sb.append("\"candidates\":[],\"candidatesOmitted\":").append(candidates.size()).append(',');
        }
        field(sb, "chosen", chosen).append(',');
        field(sb, "reason", reason);
        return sb.append('}').toString();
    }

    private static StringBuilder field(StringBuilder sb, String name, String value) {
        sb.append('"').append(name).append("\":");
        if (value == null) {
            return sb.append("null");
        }
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.append('"');
    }
}
