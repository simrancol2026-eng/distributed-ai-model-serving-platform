# Evidence index — Sprint 2 docs rehearsal at c818172

Packaging of the existing rehearsal. These files were not regenerated for this commit.

Source commit: `c818172072d4c8397a4689395a05d95dfb7b3d25` (`docs: record verified Sprint 2 work and remaining closeout gates`).
The report, screenshots, and logs below are the copies from that rehearsal (`/cursor/stores/self/artifacts/docs-c818172`). Two restart logs that the rehearsal wrote and did not place in that store are included from the same run's temp directory and named as such.

Docker was not installed on that run and was not invoked. The report marks Docker BLOCKED. No Docker log is included.

The rehearsal used real PostgreSQL 16.15 (database `platform_demo`) and two real DistilBERT workers at revision `714eb0fa89d2f80546fda750413ed43d93601a13`, plus Spring and Vite. Literal PowerShell was not run (`pwsh` absent). The workers used `/tmp/worker-venv`, not a repository `.venv`.

No screenshot in this folder is a stubbed HTTP response. `screenshots/ui-models.png` includes the real leftover model `vd-check-20261015152836-a2b8` created by `version_dispatch_e2e_test.py` during that rehearsal.

## Files

| Path | Where it came from |
| --- | --- |
| `TEST_REPORT_docs_c818172.md` | The rehearsal report for this SHA. Native bash equivalents passed. PowerShell and Docker are BLOCKED in that report. |
| `screenshots/ui-inference.png` | Headed Chrome workspace after sending "This movie is wonderful." Real PostgreSQL and real DistilBERT. Request id is not on the page. |
| `screenshots/ui-models.png` | Headed Chrome Models page. Real registry rows, including the version-dispatch leftover. |
| `screenshots/desktop-ui-and-curl.png` | Same rehearsal desktop: UI plus a terminal showing the UTC time and the SHA. |
| `screenshots/desktop-curl-health.png` | Restart health curl and versions curl in a terminal, with UTC time and the SHA. |
| `logs/spring.log` | Spring pid 117301 on `platform_demo`. Flyway applied V1–V3 and logged the JDBC URL. No password. |
| `logs/spring-restart.log` | Restart on the same database. Schema already at v3. No migration. |
| `logs/worker-a.log` | Worker A from `/tmp/worker-venv`, port 8001, pid 117100. |
| `logs/worker-b.log` | Worker B from `/tmp/worker-venv`, port 8002, pid 117090. |
| `logs/worker-a-restart.log` | Restart worker A, pid 119791. Recovered from the rehearsal temp directory; not in the original store copy. |
| `logs/worker-b-restart.log` | Restart worker B, pid 119790. Recovered from the rehearsal temp directory; not in the original store copy. |
| `logs/seed1.log` | First `seed_registry.py` run. Created `sentiment-distilbert` v1. |
| `logs/seed2.log` | Second seed. Same metadata, nothing overwritten. |
| `logs/present.log` | Versioned POST, GET, and the six-request alternation. |
| `logs/round_robin.log` | `round_robin_e2e_test.py` 23/23. |
| `logs/version_dispatch.log` | `version_dispatch_e2e_test.py` 19/19. Left `vd-check-20261015152836-a2b8`. |
| `logs/routing_decision.log` | `routing_decision_e2e_test.py` 38/38. |
| `logs/ui-rehearsal.log` | Playwright transcript of the models page and the workspace send. |
| `logs/npm-ci.log` | `npm ci`: 536 packages, 537 audited, 0 vulnerabilities. |
| `logs/vite.log` | Vite with `--host 127.0.0.1 --port 5173 --strictPort`. |
| `logs/vite-restart.log` | Vite again on 5173 after the process restart. |
