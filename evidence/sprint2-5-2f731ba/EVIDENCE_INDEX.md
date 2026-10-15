# Evidence index — Sprint 2 #5, commit 2f731ba

Packaging of the existing PASS run. No tests were rerun for this commit.

Source commit: `2f731ba2991cbcfbb68191e0c620f3b89da1b238` (`#5 repair: Codex source review of 6469db4`).
Files were copied from the `/tmp/rd` artifacts of that run (report dated 2026-10-14). Nothing here was regenerated.

Docker was deferred on that run (`docker: command not found`) and is unverified. No Docker log is included.

Labels used below:

- **H2 unit** — Spring tests whose datasource log says H2. Not in this folder as raw Surefire output; cited only inside the report.
- **fake DataSource** — `RequestTrackerSuppressionTest`. Not in this folder as its own log; cited only inside the report.
- **fake HTTP worker** — a test double speaking HTTP, not a DistilBERT process. Not in this folder as its own log; cited only inside the report.
- **real PostgreSQL 16 + real DistilBERT** — PostgreSQL 16.15 and a worker that loaded `distilbert-base-uncased-finetuned-sst-2-english` at revision `714eb0fa89d2f80546fda750413ed43d93601a13`.
- **frontend toolchain** — npm, tsc, Vitest, ESLint, or Vite. No database and no model.
- **browser-stubbed** — Playwright fulfilled the response in the browser. The jar did not produce that body.

## Files

| Path | Where it came from |
| --- | --- |
| `TEST_REPORT_rd_2f731ba.md` | The PASS report for this SHA. It records all four kinds of result and keeps them separate: H2 unit (Maven 86, including `RoutingDecisionLogInjectionTest` on H2 plus the in-process FakeWorker), fake DataSource (`RequestTrackerSuppressionTest`), fake HTTP worker (the CR/LF injection against real PostgreSQL, row 22), and real PostgreSQL 16 plus real DistilBERT workers (Flyway, decision agreement, e2e, lock, outage, UI success). Docker is marked BLOCKED in that report and was not rerun. |
| `screenshots/routing-success.png` | real PostgreSQL 16 + real DistilBERT. Headless Chrome against this SHA. One real worker (`model-a`, revision `714eb0fa…`) and database `registry_rd`. Badge text: Backend decision. Eligible 1 of 1. |
| `screenshots/routing-pending.png` | real PostgreSQL 16 + real DistilBERT for the decision on screen (the previous request, id from the success above). The second POST was held by Playwright and never reached the backend, so the "Previous request" badge and the cleared Eligible values are a UI state on top of that real decision. |
| `screenshots/routing-decision-unavailable.png` | **browser-stubbed.** Playwright returned HTTP 200 with no `routing` field (request id `00000000-0000-0000-0000-000000000001`). This jar's real success body includes `routing`. Not a PostgreSQL row and not a model result. |
| `screenshots/routing-not-sent.png` | Local UI reject. A file was attached and the page did not call the backend. The app was still pointed at the real Spring process, but this action stored nothing and ran no model. |
| `screenshots/routing-backend-error.png` | real PostgreSQL 16 + real DistilBERT worker, then the worker process was stopped. Spring on `registry_rd` returned 503 `WORKER_UNAVAILABLE` for `127.0.0.1:8001`. The footer still said Backend connected. |
| `ui-check.log` | Playwright transcript of the five screens above. Same mix: real success and real worker-down error; pending hold; browser-stubbed decision unavailable; local Not sent. |
| `spring-ui.log` | real PostgreSQL 16 + real DistilBERT. Spring pid 112030, the UI run. Flyway log: `jdbc:postgresql://localhost:5432/registry_rd (PostgreSQL 16.15)`, schema v3, no password. Datasource line: `product=PostgreSQL version=16.15`. Routing decision for request `e72c0613-d5dc-42ca-b059-6e4b3e81c47a` (the success screenshot) and `97552fca-29bf-442f-912c-14175a819af3` (worker down). |
| `vite.log` | frontend toolchain. Vite dev server for the UI run, `http://127.0.0.1:5173/`. No database. |
| `vitest.log` | frontend toolchain. Vitest 12/12 in 1.58 s. These are frontend unit tests, not H2 and not PostgreSQL. |
| `tsc.log` | frontend toolchain. `tsc --noEmit` exit 0. |
| `eslint.log` | frontend toolchain. 10 warnings, 0 errors, all `react-refresh/only-export-components`. |
| `npm-ci.log` | frontend toolchain. `npm ci`: 536 packages added, 537 audited, 0 vulnerabilities. |
| `vite-build.log` | frontend toolchain. `vite build` exit 0. |

H2 unit output, the fake DataSource test, and the fake HTTP worker injection were not copied into this folder. Their counts and request ids are only in `TEST_REPORT_rd_2f731ba.md`.
