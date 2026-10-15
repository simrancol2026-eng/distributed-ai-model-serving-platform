FROM: Grok
TO: Claude, Codex-ChatGPT
TYPE: TEST REPORT
PHASE: Sprint 2 docs delta review of PR #5 (independent; no code-branch push)
COMMIT: 9fe2c77878c856eccb8f20ff0b56881180f70093
COMPARED WITH: 4e2e0a0e88038cc4104bf89294853839b3ab303d
BRANCH: codex/sprint2-docs-demo (fresh clone, detached; product tree not modified; not pushed)

Subject of `9fe2c77`: "docs: pin both demo shells to port 5173 and clarify sprint status".

This review did not rerun the c818172 rehearsal or the 4e2e0a0 fence run. It compared this SHA with `4e2e0a0` and executed the three checks below on a fresh native stack. Docker was not run. No app code was changed.

## Environment

- Fresh clone `/tmp/docs-9fe2c77` at `9fe2c77878c856eccb8f20ff0b56881180f70093`.
- Ubuntu, OpenJDK 21.0.12.1, Node, npm, Python 3.12, PostgreSQL 16.15.
- Fresh database `docs_9fe2c77`, role `docs_9fe2c77`. Public tables before Spring: 0 (`logs/db-setup.log`). Password stayed in the existing mode-600 file and is not in these files.
- One real DistilBERT worker, `WORKER_ID=worker-a`, revision `714eb0fa89d2f80546fda750413ed43d93601a13`, uvicorn on `127.0.0.1:8001` (pid 147406).
- Backend: `./mvnw -B spring-boot:run` from `backend/spring-boot` with `PLATFORM_BACKEND_PORT=8080` and `PLATFORM_WORKER_URL=http://localhost:8001`. `PLATFORM_WORKER_URLS` and `PLATFORM_ROUTING_POLICY` were unset. Java pid 147597. Tomcat on 8080. Flyway applied V1–V3 to empty `docs_9fe2c77` in 0.035 s, now at v3. Log line: `worker pool: 1 worker(s), round-robin order [localhost:8001]`.

## Diff against 4e2e0a0

`git diff --stat 4e2e0a0e88038cc4104bf89294853839b3ab303d` (`logs/diff-stat.txt`):

```
 README.md    | 7 ++++---
 docs/DEMO.md | 8 ++++----
 2 files changed, 8 insertions(+), 7 deletions(-)
```

`git diff --name-status` (`logs/diff-name-status.txt`): `M README.md`, `M docs/DEMO.md`.

`git diff --name-only` restricted to `*.java`, `*.ts`, `*.tsx`, `*.py`, `*.sql`, `*.xml`, `*.json`, `*.yml`, `*.yaml` produced an empty file (`logs/diff-app-code.txt`, 0 lines). The full docs patch is `logs/diff-docs.patch`.

## Checks

| Check | Result | Command | Actual output |
| --- | --- | --- | --- |
| No app code changed vs `4e2e0a0e88038cc4104bf89294853839b3ab303d` | PASS | `git diff --stat 4e2e0a0e88038cc4104bf89294853839b3ab303d`; `git diff --name-status` that SHA; `git diff --name-only` that SHA for `*.java` `*.ts` `*.tsx` `*.py` `*.sql` `*.xml` `*.json` `*.yml` `*.yaml` | Stat: `README.md` 7 lines, `docs/DEMO.md` 8 lines, `2 files changed, 8 insertions(+), 7 deletions(-)`. Name-status: `M README.md` and `M docs/DEMO.md`. App-code name list: empty (`APP_CODE_LINES=0`). |
| README status row says Docker WIP, consistent with the banner | PASS | Read `README.md` at this SHA, lines 25, 51, and 55 | Banner (line 25): `**Docker — WORK IN PROGRESS:** the worker Dockerfile exists; image build/run verification and the full multi-service container setup remain incomplete. The Sprint 2 demo uses native processes.` Status row (line 51): `\| Docker \| **WORK IN PROGRESS / UNVERIFIED** \| Worker Dockerfile exists; image build/run validation and the full multi-service setup remain incomplete. Native processes are used for the demo. \|` Closing line (line 55): `PLANNED items are not implemented. Docker work has started but remains unverified.` The row and the banner both say work in progress. This is a text check. Docker was not executed. |
| Documented frontend bash on a native backend at 8080; Vite serves 5173; one inference through CORS | PASS | README §3 bash, from `/tmp/docs-9fe2c77`: `cd frontend`; `npm ci`; `cp .env.example .env.local`; `npm run dev -- --port 5173 --strictPort`. Then `curl -sS -D - http://localhost:5173/` (headers `logs/vite-headers.txt`, body `logs/vite-index.html`). Then `curl -sS -D logs/cors-headers.txt -o logs/cors-body.json -H 'Origin: http://localhost:5173' -H 'Content-Type: application/json' -H 'Accept: application/json'` POST of a TEXT inference to `http://localhost:8080/api/inference`, and an OPTIONS preflight with `Origin: http://localhost:5173`, `Access-Control-Request-Method: POST`, `Access-Control-Request-Headers: content-type` (`logs/cors-options.txt`). UI: Playwright opened `http://127.0.0.1:5173/`, filled `This movie is wonderful.`, clicked Send request, waited for text `POSITIVE`. | `NPM_CI_RC=0` (added 536 packages, audited 537, 0 vulnerabilities). `.env.local` from the example: `VITE_API_BASE_URL=http://localhost:8080`. Vite log: `vite dev --port 5173 --strictPort`, `VITE v8.1.5  ready in 886 ms`, `Local:   http://localhost:5173/`. Page fetch: `HTTP/1.1 200`, `content-type: text/html; charset=utf-8`, body 55140 bytes. CORS POST: `HTTP/1.1 200`, `Access-Control-Allow-Origin: http://localhost:5173`, `Content-Type: application/json`. Body `requestId` `cb153821-5340-4413-9dcb-3d02ccb0804d`, worker `worker-a`, endpoint `localhost:8001`, label `POSITIVE`, score `0.9998793601989746`, `totalLatencyMs` 144. Spring at `2026-10-15T19:44:34.167Z`: `inference ok requestId=cb153821-5340-4413-9dcb-3d02ccb0804d routedTo=localhost:8001 worker=worker-a ... label=POSITIVE totalMs=144`. OPTIONS: `HTTP/1.1 200`, `Access-Control-Allow-Origin: http://localhost:5173`, `Access-Control-Allow-Methods: GET,POST`, `Access-Control-Allow-Headers: content-type`, `Access-Control-Max-Age: 1800`. UI script printed `UI_SHOT_OK` and exited 0. DOM (`screenshots/ui-inference.txt`): `Backend connected`, `This movie is wonderful.`, `POSITIVE (confidence 100.0%) — sentiment-analysis`, worker `worker-a`, eligible `1 of 1`, reason `no model requested: round-robin over all 1 worker(s)`, latency `54 ms`. Spring at `2026-10-15T19:44:46.456Z`: `requestId=2afc063c-546b-4217-afb4-f0412a0e60f1` `totalMs=54` `label=POSITIVE`. Screenshot: `screenshots/ui-inference.png`. |
| `--strictPort` fails clearly when 5173 is occupied | PASS | While the first Vite still held 5173: `cd frontend`; `npm run dev -- --port 5173 --strictPort` | npm script line `vite dev --port 5173 --strictPort`. Then `error when starting dev server:` and `Error: Port 5173 is already in use`. `STRICT_RC=1`. It did not print a second `Local:` URL. Full log: `logs/strictport-occupied.log`. |

## Not claimed

Docker image build and the multi-service container setup were not run. The README row marks them unverified. That remains unverified.
