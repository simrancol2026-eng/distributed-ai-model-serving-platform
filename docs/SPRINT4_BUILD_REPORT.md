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

## Review-fix validation, October 28

Claude reviewed published `d18a74921e643bda3d6c8b10c4020c551c8eb90d`
and approved with a recommended budget/cooldown fix. Grok reported an independent
150-test pass plus PostgreSQL/fake-worker and single-run real-model experiments;
supporting artifacts were requested and are still pending inspection at this
revision. These reported experiments are not Codex-verified execution evidence.

The follow-up separates BUDGET_EXPIRED from genuine TIMEOUT/CONNECT_TIMEOUT,
avoids worker cooldown on budget expiry, exposes BUDGET_EXHAUSTED/INTERRUPTED
before dispatch, retains selection evidence, and clarifies process-local cursors.
Full backend rerun using the explicit agent command above: **155 tests across
33 classes, zero failures/errors/skips, exit 0**. Local log:
`sprint4-review-fix-tests.log`. Java 21.0.12.1, Windows, H2/fake/loopback only.
Five new regression tests distinguish budget-vs-read/connect timer expiry and
verify alternate budget expiry leaves cooldown zero while a true read timeout
still penalizes the worker. The controller budget-expiry test injects the
failure deterministically; Grok's real delayed-A/short-remainder-B retest is
requested on the newly published SHA. Diff check passed.

The revised commit still requires Claude's delta review and Grok's delta test
before merge. No Sprint 4 completion, client-disconnect or Docker claim.

### Fair-allocation refinement

Claude requested changes on the first follow-up (`b959b09`): with the default
10s budget/30s read timeout, unconditional budget-expiry suppression could hide
a genuinely hung worker. The final rule gives timeout feedback when an attempt
received at least half of `min(read timeout, original dispatch allowance)`;
lesser allocations do not penalize the worker on budget expiry. Both terminate
BUDGET_EXHAUSTED without retry. Budget errors now have an accurate response
message. A request-timer connect timeout at actual budget exhaustion follows
this rule; a shorter configured connect timeout remains CONNECT_TIMEOUT.

Full Windows/H2/fake/loopback suite after the refinement: **158 tests, 33
classes, zero failures/errors/skips, exit 0**, same explicit agent command.
Log: `sprint4-fair-allocation-tests.log`. Added deterministic full-default-budget
classification, tiny-remainder classification, and hung-worker cooldown/next
RR selection regression coverage. The real loopback stalled-body test now
verifies a full allocation is TIMEOUT with fairAllocation true. Independent
review and delayed-worker delta execution remain merge gates on the published
refinement SHA.

Grok's baseline ZIP F0CD4H71081 has been inspected (SHA256
0195ff8782f03d1662ea606c9a397c4baf090d1e78a5b2d4bd9a29bfa4c97a68).
It contains Maven reports, scripts, PostgreSQL rows and worker/backend logs.
His timing correction separates a 1001ms single-attempt timeout from 10ms/2ms
second attempts in different requests. The corrected concurrency dataset has
51 rows for a 50-concurrent workload, so no 51-concurrent claim is made.
Backend real-worker evidence shows 5ms pre-send failure followed by 13ms
successful alternate, total20ms; 25ms client wall and score0.99976 remain
reported summary values because the original final response JSON was
overwritten. No screenshots or new real-model rerun were supplied.
