# Sprint 2 closeout and evidence

Updated October 15, 2026. All five implementation items have been merged; full sprint closeout is still pending documentation review, demo rehearsal, retrospective and owner review. The older Phase 1 end-to-end milestone is not completion of Trello Sprint 1.

## Completed implementation items

| Work item | Tested commit | Merge commit | Evidence scope |
|---|---|---|---|
| Registry and versions | f581a1528ad438012490bf6705f648990510d3b9 | 3ace7ad841dcdb15fe669ccdb02282ffd87d1d57 | PostgreSQL migration, API conflicts and persistence; H2 unit tests separately labeled |
| Two workers and round-robin | 0a056a9db815dc108ea345b9a42b9f3f2fbf405b | 6ebec8fe0a64e3521189f2c61ab6a0ee22cd1004 | Separate real worker processes and alternating requests |
| Version-aware dispatch | 4351f23fee3939ed19b32db5d0bd67fcd9376e10 | 856caaaa4d5f363df5a4d913f07e5a96db830d6c | Exact artifact/revision selection; no substitution |
| Persistent request lifecycle | e70b733075759936f7bfd83bb79aee87499a651e | 87eaf0fa0ed685baadd7988c01de085b396defa5 | Native PostgreSQL persistence, locks/outages, bounded best-effort tracking; subsequent branch changes were documentation |
| Routing decisions and frontend states | 2f731ba2991cbcfbb68191e0c620f3b89da1b238 | 1c1d932eba0dd75a8920186d4571cce0eeaba470 | Log/response/database consistency, V2→V3 preservation, real worker and UI checks |

Codex verified the last merge's tree is identical to its tested head: 31795af55cc7810fe0fa08963354839760ea810a. Grok executed the independent tests; Codex reviewed source and supplied logs, without claiming a local rerun.

## Durable evidence and provenance

- [Repository](https://github.com/simrancol2026-eng/distributed-ai-model-serving-platform)
- [Routing evidence at immutable commit 3cb00e8](https://github.com/simrancol2026-eng/distributed-ai-model-serving-platform/tree/3cb00e8a3bbd8fd7edf404f4f878a08b9ba25c3f/evidence/sprint2-5-2f731ba): report, frontend logs and five screenshots.
- [Raw evidence delivery in the task thread](https://aicapstone-workspace.slack.com/archives/C0C8HENCGD9/p1792075176038079): migration, routing, lock/outage and end-to-end logs.
- [Codex acceptance](https://aicapstone-workspace.slack.com/archives/C0C8HENCGD9/p1792075613152849).
- [Lifecycle acceptance](https://aicapstone-workspace.slack.com/archives/C0C8HENCGD9/p1791943786264619).

The latest evidence was executed October 14 and uploaded October 15; upload time is not execution time. Environment: Ubuntu 24.04.5, Java 21.0.12.1, PostgreSQL 16.15, Python 3.12.3, Node 22.14.0. Real DistilBERT revision: 714eb0fa89d2f80546fda750413ed43d93601a13. Two workers were used for routing checks; the recorded frontend run used one real worker.

## Validation distinctions

- Maven: 86 passing tests. Spring contexts use H2; pure unit, fake HTTP worker and fake DataSource tests are separately identified in the report. These are not real PostgreSQL execution.
- Frontend: 12 passing tests, TypeScript and production build exit 0, ESLint 0 errors and 10 warnings.
- Native integration: routing-decision script 38/38, version-dispatch script 19/19, round-robin script 23/23, request tracking save 17/17 and restart verify 46/46, Phase 1 real-model script 8/8.
- Migration: V2→V3 retained eight old successful rows with null routing; fresh database applied V1–V3.
- UI DOM evidence: real success, cleared eligibility while a second request is pending, local rejection, and backend failure. The decision-unavailable screenshot uses a browser-stubbed HTTP 200 without routing; it is not a real backend response.
- The latest version-dispatch script uses an unserved synthetic revision for rejection. It does not prove two real revisions were executed in that run.
- A stopped worker plus database outage took 32.013 seconds: 30 seconds worker timeout plus one failed completion write. This does not measure failure of the initial tracking write followed by a long worker operation.
- Open-gate lock burst: maximum observed request time 4.058 seconds, with one tracking failure per request. This is an observed run, not a universal latency guarantee.
- Docker: deferred/unverified. No Docker pass is claimed.

## Architecture and operating limits

React calls the unified Spring inference API. Without a model selector, Spring uses round-robin over configured workers. With a selector, it resolves registry metadata and filters workers by reported artifact and revision before round-robin selection. A single decision snapshot explains the selection. PostgreSQL stores registry data and best-effort request/decision records.

Worker identity is cached and does not establish live health. A matching but stopped worker can be selected and return 503; there is no automatic failover. Tracking failures can leave missing or incomplete rows while inference succeeds. Malformed JSON rejected before the controller is not tracked. Registered model metadata does not load a model.

The detailed API, configuration and startup reference remain in [README](../README.md); [DECISIONS](../DECISIONS.md) records the design rationale. [DEMO](DEMO.md) provides the native-stack rehearsal sequence.

## Remaining closeout work

1. Independently review documentation and rehearse the demo against a recorded commit.
2. Visually inspect the original frontend screenshots and obtain actual backend screenshots with provenance. Codex has reviewed the raw UI logs but has not yet visually inspected the images.
3. Produce and visually review the retrospective PDF with one line per work item, real frontend/backend screenshots, repository link, tested commits, lessons and remaining gaps; retain a local copy and share in the sprint thread.
4. Reconcile Trello against the verified scope. Do not mark Sprint 1 complete based on Phase 1; its Docker item remains unverified.
5. Present the complete Sprint 2 package to Dhir. Sprint 3 begins only after Dhir's review and authorization.

## Lessons and next steps

Source review and unit tests alone did not establish database failure behavior; real PostgreSQL lock/outage evidence was necessary. A single routing snapshot avoids extra health probes and accidental double advancement of round-robin state. UI labels must distinguish current requests, previous responses, local rejection and server failure. Preserve artifacts with exact commits early so coordination does not depend on a running assistant or expiring download links.

Next work is documentation, demo rehearsal and the retrospective. Intelligent routing and distributed reliability remain future sprint work.
