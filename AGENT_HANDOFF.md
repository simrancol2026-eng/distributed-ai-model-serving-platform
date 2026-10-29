# Agent handoff

Read this first when resuming work. GitHub is the source of truth; this file says where to look.
Team coordination happens in Slack `#capstone-ai-team` (one thread per task, `FROM/TO/TYPE/PHASE/COMMIT` header).

Last updated: 2026-10-29 by Claude and Codex.

## Current sprint and feature

- **Sprint 4 — distributed reliability.** First slice: opt-in bounded failover for synchronous prediction
  (owner **Codex**). Design agreed in the Slack thread started 2026-10-24 09:44 EDT: at most 2 attempts,
  distinct endpoints, retry only positively classified pre-send connection failures and worker HTTP 502/503,
  no timeout retry (including connect timeout), one dispatch budget (`worker.failover.budget-ms`, default 10000),
  default disabled. Real client-disconnect cancellation is deferred.
- **Bounded failover merged:** PR #12 as `8044116` (reviewed/tested head `58db847`, tree `001cea4`).
  Codex and Grok: 158 backend tests passing (H2/fake/loopback; Grok also supplied PostgreSQL delta evidence).
  Claude APPROVE exact `58db847`; Grok delta PASS. Merge tree equals that head. No merged-main rerun or
  real-model rerun on the revised head is claimed. Sprint 4 demo/screenshots/reviewed retrospective remain open.

## Merged

| Item | Merge | Tested head |
|---|---|---|
| Phase 1 end-to-end path | `f7d11b0` | `8bfc496` |
| Model registry + versions | `3ace7ad` | `f581a15` |
| Two workers, round-robin | `6ebec8f` | `0a056a9` |
| Version-aware dispatch | `856caaa` | `4351f23` |
| Request lifecycle tracking | `87eaf0f` | `e70b733` |
| Routing decision logs | `1c1d932` | `2f731ba` |
| Least-in-flight (Sprint 3) | `a54aba5` | `b75e0ad` |
| Health/latency (Sprint 3) | `4a2c343` | `ed28851` |
| Sprint 3 docs (PRs #8–#10) | `39e59e7`, `dec1b5b`, `2aca70a` | docs only |
| Bounded failover (Sprint 4 code slice) | `8044116` | `58db847` |

`main` = `8044116` as verified 2026-10-29 before this documentation merge. Sprint 3 is closed (retrospective published 2026-10-24).

## Open pull requests

- **PR #3** `ci/docker-integration-tests` (`273e185`): Docker worker build + `/health` smoke test.
  CI run 38541943495 succeeded on 2026-10-14 (head `273e185`, tested merge ref `5c0219f`); Grok confirmed the log (D-048). Waiting for Codex review.
- **PR #11** `claude/docker-status-and-handoff`: this file, README Docker/status wording, D-048. Reviewed by Claude and Grok; final wording correction applied by Codex.

Merge rule: per Dhir (2026-10-15), a PR may be merged once its review and tests/checks pass; no separate approval step.

## Blockers

- Local Docker on the owner's PC: Docker Desktop's WSL engine does not start (2026-10-24; a factory reset was chosen in its error dialog). Local container checks cannot run there.

## Last tests executed

- Claude: none (source review only). Grok: fresh-clone matrix on `d18a749`, 150/150 Maven tests plus PostgreSQL 16.4 and real DistilBERT fault runs (2026-10-28).
- Docker evidence: PR #3 Actions run (Success) and Grok's reading of its log.

## Pending

- **Codex:** finish PR #11 documentation integration; review PR #3; coordinate Sprint 4 demo/retrospective.
- **Claude:** PR #12 source review complete; Sprint 4 documentation/demo review.
- **Grok:** PR #12 delta PASS and PR #11 docs diff check complete; provide evidence support for Sprint 4 closure.

## Next step

PR #12 is merged. Complete the Sprint 4 demo with actual frontend/backend screenshots and reviewed retrospective before formal sprint closure.
Docker: after PR #3 is approved, add a `/predict` call to the smoke test as the next small increment.
