# Sprint 4 candidate build report

Owner: Codex / ChatGPT. Executed October 28, 2026 (America/New_York).
Runtime/test source commit: `660105814cd08fdfc02758055c4234dba1a1fdd6`.
Base: `2aca70aea3466949a446ce7a573ca683bad19696`.
Branch: `codex/sprint4-bounded-failover`. Subsequent report-only commit does not
change the tested Java source. Repository:
https://github.com/simrancol2026-eng/distributed-ai-model-serving-platform

## Verified results

Full backend Maven test run: **150 tests across 33 suites, 0 failures, 0 errors,
0 skipped**, process exit 0. Results counted from Surefire XML after completion.
Windows, Java 21, local H2 and fake workers; no real model execution in this run.

Command from `backend/spring-boot` (replace the local agent path as appropriate):

```powershell
.\mvnw.cmd -q '-DargLine=-javaagent:C:/Users/dhirs/.m2/repository/org/mockito/mockito-core/5.23.0/mockito-core-5.23.0.jar' test
```

An initial ordinary Maven run stalled during Mockito dynamic attachment with a
Windows named-pipe access error. It was stopped and is not counted as a pass.
Explicit startup of the already installed Mockito agent resolved that test
harness issue; the complete rerun above passed. No production runtime agent or
dependency changes were made. `git diff --check` also passed.

Coverage added in this candidate includes:
- Conservative retry classification, connect timeout separation, cycle-safe
  cause traversal, status allowlist, interruption preservation.
- Shared monotonic budget, sub-millisecond remainder, overflow validation.
- Fake transport future cancellation; real loopback HTTP stalled-body timeout
  using a 500ms budget and a 250ms scheduling allowance (assertion passed).
- Controller fail-then-success, cap of two, no alternate, no timeout retry,
  disabled compatibility, HTTP allowlist, interruption before/during a call,
  and cold-start rejection followed by success after observation.
- Every routing policy: freshness at 9s/11s around the default 10s limit,
  unobserved-worker exclusion, exact revision, no endpoint reuse, and 50
  concurrent two-reservation failure simulations per policy with zero leaked
  reservations. These are pool simulations, not 50 real-model requests.
- H2 final endpoint/worker-id consistency, terminal-row overwrite protection,
  legacy endpoint preservation, and FAILED/no dispatchedAt for pre-selection
  freshness rejection. The latter tests persistence directly, not a full HTTP
  plus PostgreSQL experiment.
- Attempt JSON/log evidence and candidate-omission preservation.
- Existing backend regression suites, including default-disabled routing.

## Independent review and remaining evidence

This is a review candidate, not sprint completion. Claude's independent review
and Grok's fresh-clone execution on the published head are still pending.
Please scrutinize deadline boundaries, eligible-set selection, reservations,
attempt semantics, and tracking consistency.

Outstanding independent experiments: PostgreSQL outage/tracking latency,
real DistilBERT failure and recovery, background-poller timing to first success,
actual worker death between health observation and dispatch, full controller
concurrency under network faults, and reviewed frontend/backend demo evidence.
Existing tests prove narrower fake/H2 behaviors; no speedup result is asserted.

Budget includes dispatched tracking elapsed time, but JDBC itself is not
cancelled by the deadline. Tracking is invoked per attempted worker, although
only the first RECEIVED-to-DISPATCHED transition updates the row. HTTP-body
waiting is bounded; CPU decoding, DB work and request completion are not a
hard wall-clock SLA. Local cancellation cannot prove remote termination.
Attempts represent worker-call attempts, not proof of model execution.
Reservations are local to one backend. No durable idempotency or exactly-once
guarantee. Docker and the full container stack remain unverified.

Raw local logs retained by Codex: `sprint4-full-tests-agent.log` (successful
run), `sprint4-full-tests.log` (stopped attachment run), and Surefire reports in
the local backend target directory. These are not committed because test
reports can contain environment-specific properties.
