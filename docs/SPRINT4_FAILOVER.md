# Sprint 4 bounded failover review candidate

Enable with `worker.failover.enabled=true` (`WORKER_FAILOVER_ENABLED=true`).
Default is false. `worker.failover.budget-ms` (`WORKER_FAILOVER_BUDGET_MS`)
defaults to 10000 and must be positive and representable in nanoseconds.
Round-robin remains the default policy. The disabled dispatch path is retained.

## Contract

- At most two distinct configured endpoints per request. Retry only explicit
  pre-send connection/DNS failures and worker HTTP 502/503. Never retry timeout
  (including CONNECT_TIMEOUT), reset/unknown transport failure, interruption,
  malformed response, model mismatch, HTTP 4xx, 500 or 504.
- Both selections use background health observations and exact artifact/revision
  eligibility. No foreground probe runs inside dispatch or the reservation lock.
  Selection/reservation is atomic within this backend process; release precedes
  alternate selection on all normal and exceptional outcomes.
- Enabled cold start and all-stale observations return 503 NO_FRESH_OBSERVATION
  with no attempts. Fresh unhealthy workers give NO_HEALTHY_WORKER; proven
  version absence gives 422 NO_ELIGIBLE_WORKER. An unobserved worker cannot be
  selected even when another worker has a fresh observation.
- Observation lifetime is `2 * (2000ms poll interval + probe timeout) + 2000ms`:
  10 seconds with the default 2-second probe timeout. Existing two-failure/
  one-success health hysteresis and cooldown override remain in effect.
- RR and LIF select within that eligible set; health/latency uses its existing
  score. RR advances on each selection, including an alternate.

## Budget and evidence limits

One monotonic dispatch budget starts after request validation/registry lookup,
before selection, and is reused across attempts. HTTP waiting includes response
body receipt and is capped by remaining budget and configured read timeout.
Timeout/interruption cancels the local response future; it cannot prove remote
execution stopped. There is no exactly-once promise: even HTTP 503 may follow
remote execution. No retry is performed for ambiguous timeout/reset failures.

Tracking `dispatched` is invoked per attempt (up to twice). Its SQL transitions
only a RECEIVED row; final completion updates the endpoint to match the final
worker. Tracking overhead spends the remaining dispatch allowance but JDBC is
not cancellable by this deadline. Validation, registry, initial/final tracking,
JSON decoding and scheduling mean this is not a total HTTP response latency SLA.
No client-disconnect cancellation guarantee is made for synchronous MVC.

Success and error routing evidence includes at most two selected worker-call
attempts with endpoint, outcome, elapsedMs and failureClass, plus terminalReason.
Elapsed includes the dispatched tracking call. An attempt is not proof the worker
executed; a connection failure or expired allowance can precede sending a body.
Candidate omission in stored JSON retains attempt evidence. Pre-selection failure
is tracked FAILED with dispatchedAt null when best-effort storage succeeds.
Counters/reservations are process-local, not shared across backend replicas.

## Review and testing

See SPRINT4_BUILD_REPORT.md for actual checks and tested source provenance.
Claude owns independent design/diff review; Grok owns independent execution on
the published commit, including PostgreSQL and real-model fault evidence.
This candidate does not close Sprint 4. Docker/full-stack deployment remains
unverified. Distributed coordination and durable idempotency are not delivered
by this slice.
