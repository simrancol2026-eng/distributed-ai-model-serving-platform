# Agent handoff

Read this first when resuming work. GitHub is the source of truth; this file says where to look.
Team coordination happens in Slack `#capstone-ai-team` (one thread per task, `FROM/TO/TYPE/PHASE/COMMIT` header).

Last updated: 2026-10-25 by Claude.

## Current sprint and feature

- **Sprint 4 — distributed reliability.** First slice: opt-in bounded failover for synchronous prediction
  (owner **Codex**). Design agreed in the Slack thread started 2026-10-24 09:44 EDT: at most 2 attempts,
  distinct endpoints, retry only positively classified pre-send connection failures and worker HTTP 502/503,
  no timeout retry (including connect timeout), one dispatch budget (`worker.failover.budget-ms`, default 10000),
  default disabled. Real client-disconnect cancellation is deferred.
- **No Sprint 4 code is published yet.** Codex's repository access was pending on 2026-10-24.

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

`main` = `2aca70a` as of 2026-10-25.

## Open pull requests

- **PR #3** `ci/docker-integration-tests` (`273e185`): Docker worker build + `/health` smoke test.
  CI run 38541943495 succeeded; Grok confirmed the log (D-048). Waiting for Codex review and **Dhir's approval to merge**.
- **Docs PR** `claude/docker-status-and-handoff`: this file, README Docker wording, D-048.

## Blockers

- Local Docker on the owner's PC: Docker Desktop's WSL engine does not start (2026-10-24; a factory reset was chosen in its error dialog). Local container checks cannot run there.
- Sprint 4 implementation waits on Codex publishing a SHA.

## Last tests executed

- No new runtime tests by Claude this session. Evidence used: PR #3 Actions run (Success) and Grok's reading of its log.

## Pending

- **Grok:** run the Sprint 4 failover matrix on Codex's published SHA (fresh clone).
- **Claude:** source review of Codex's failover diff against the points in Claude's 2026-10-24 design review
  (failure classification, per-attempt timeout vs budget, alternate selection per policy, final-endpoint tracking, `attempts[]` in decision JSON).
- **Codex:** review of PR #3 and of the docs PR.
- **Dhir:** merge approvals for PR #3 and the docs PR.

## Next step

When Codex posts the failover BUILD REPORT + SHA in the Sprint 4 thread, review that diff and post findings in the same thread.
Docker: after PR #3 is approved, add a `/predict` call to the smoke test as the next small increment.
