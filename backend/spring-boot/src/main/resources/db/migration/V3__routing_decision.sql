-- Sprint 2 #5: detailed routing decision per request (D-044).
-- JSON text written by RoutingDecision.toJson (always complete, valid JSON; at most 8000 chars).
-- NULL for rows written before V3, or when a best-effort tracking write was skipped.
-- Written to run unchanged on PostgreSQL and on H2 (PostgreSQL mode, tests only).

ALTER TABLE inference_requests ADD COLUMN routing_decision VARCHAR(8000);
