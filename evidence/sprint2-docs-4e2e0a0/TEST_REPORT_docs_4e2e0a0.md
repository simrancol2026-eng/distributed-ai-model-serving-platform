FROM: Grok
TO: Claude, Codex-ChatGPT
TYPE: TEST REPORT
PHASE: Sprint 2 docs correction review (independent; no code-branch push)
COMMIT: 4e2e0a0e88038cc4104bf89294853839b3ab303d
BRANCH: codex/sprint2-docs-demo (detached checkout; not modified; not pushed)

Reviewed docs only. App code was not changed and this branch was not pushed. Parent of this SHA is `bee81d0731adcf7e7c12ed2cb42f317374d0baa2`. Subject: "Correct demo guidance from independent rehearsal review".

`git diff --stat c818172072d4c8397a4689395a05d95dfb7b3d25 4e2e0a0e88038cc4104bf89294853839b3ab303d` is `README.md`, `docs/DEMO.md`, `docs/SPRINT2_CLOSEOUT.md`, `docs/SPRINT2_PRESENTATION.md` (4 files, 242 insertions, 8 deletions).

`git diff c818172072d4c8397a4689395a05d95dfb7b3d25 4e2e0a0e88038cc4104bf89294853839b3ab303d -- . ':(exclude)README.md' ':(exclude)docs'` produced no output. App code is unchanged.

Docker was not used. `docker` is not on PATH (`docker: command not found`, exit 127). That is not a pass.

## Environment

- Ubuntu 24.04.5 LTS, kernel 6.12.94+, x86_64.
- Java OpenJDK 21.0.12.1. Node v22.14.0, npm 10.9.9. Python 3.12.3.
- PostgreSQL 16.15 (Ubuntu 16.15-0ubuntu0.24.04.1), cluster 16/main online on port 5432. Fresh database `docs_4e2e`, role `docs_4e2e`, created 2026-10-15T15:53:25Z by `scripts/setup_registry_db.sh` because it did not exist. Public tables before Spring: 0. Password stayed in the environment from the existing mode-600 file. It is not printed in this report.
- `pwsh` is not installed. PowerShell fences were not executed.
- The worker bash block created `model-workers/model-a/.venv`. `.gitignore` line 14 (`.venv/`) ignores it. It was not staged.

## Earlier findings

| Item | Result | What the new text says |
| --- | --- | --- |
| 1. Request ID claimed visible in the UI | FIXED | `docs/DEMO.md` present step 3: "The workspace does not display the request ID; obtain it from the POST response in browser developer tools (Network), or use the terminal request below and trace that separate request." `docs/SPRINT2_PRESENTATION.md` section 4: "The API response includes a request ID, although the current workspace does not display that ID." Section 7 says to take the UI id from the Network POST, or to trace the terminal request as a separate request. No `requestId` string in `frontend/src/**/*.tsx` at this SHA. |
| 2. README said the DB URL is not logged; Flyway logs the JDBC URL | FIXED | `README.md`: "The application's datasource summary omits the URL and credentials, but Flyway startup logs can include the JDBC database URL." This run's Spring log has both `Database: jdbc:postgresql://localhost:5432/docs_4e2e (PostgreSQL 16.15)` and `registry datasource: product=PostgreSQL version=16.15 (Ubuntu 16.15-0ubuntu0.24.04.1) activeProfiles=[default]`. The password is not on either line. |
| 3. Runbook was PowerShell only | FIXED | `docs/DEMO.md` now has "Bash equivalents (Linux/macOS)" for worker A, worker B, backend, frontend, and the health/seed/inference/six-request sequence. Those five fences were executed below. The present-step inference fence and the optional e2e fence are still PowerShell. `pwsh` is absent, so those fences were not run. They are not a pass. |
| 4. Closeout called `31795af` a commit; it is a tree | FIXED | `docs/SPRINT2_CLOSEOUT.md`: "share Git tree `31795af55cc7810fe0fa08963354839760ea810a`. That last identifier is a tree SHA, not a commit SHA." `git cat-file -t` on that object returns `tree`. `git rev-parse 31795af55cc7810fe0fa08963354839760ea810a^{commit}` fails: expected commit type, object dereferences to tree type. |
| 5. Success example missing modelId/modelVersion/routing; 404 before a round-robin turn; CORS allows localhost and 127.0.0.1 | FIXED | README success JSON includes `"modelId": null`, `"modelVersion": null`, and a `routing` object. Round-robin bullet: "Requests rejected before routing with 400, 404 or 422 do not take a turn." CORS cell: "by default http://localhost:5173 and http://127.0.0.1:5173". |
| I-3. README status said Docker PLANNED while the banner said WORK IN PROGRESS | OPEN | Banner added: "**Docker — WORK IN PROGRESS:** the worker Dockerfile exists; image build/run verification and the full multi-service container setup remain incomplete." The status table still says `Docker (multi-service), Kubernetes, autoscaling | PLANNED | model-a has a single Dockerfile (build not yet verified).` The next sentence is still "Nothing marked PLANNED exists yet." The banner and the status cell do not use the same label. |

## Docker labels

No document in this diff claims a Docker pass.

- README banner: WORK IN PROGRESS. Sprint 1 line: "the Docker image has not been verified." Worker row: "The worker Docker image is **not verified**." Routing row: "Docker remains deferred/unverified." Table cell: PLANNED, "build not yet verified" (this is I-3, still OPEN).
- `docs/DEMO.md`: "Docker is **WORK IN PROGRESS**" and "Docker remains unverified."
- `docs/SPRINT2_CLOSEOUT.md`: "Docker: **WORK IN PROGRESS**, deferred/unverified. ... No Docker pass is claimed." Closeout item 4: "its Docker item remains unverified."
- `docs/SPRINT2_PRESENTATION.md`: banner WORK IN PROGRESS; spoken line "Docker is still work in progress"; STAR result "Docker is work in progress"; Q&A "Docker is work in progress and unverified."

This host: `docker: command not found`. Docker was not run. Unverified stands.

## New bash blocks

Fresh native stack. No Docker. Database `docs_4e2e` was empty before the backend fence. The backend fence does not set database variables; the paragraph above it says to set them privately, and that was done (`PLATFORM_DB_URL` host localhost, database `docs_4e2e`, user `docs_4e2e`). Long-running processes were stopped with SIGTERM after the checks fence (exit 143). That stop is not a failure of the fence.

| Block | Result | Command | Actual output |
| --- | --- | --- | --- |
| Worker A | PASS | `cd model-workers/model-a`; `python3 -m venv .venv`; `.venv/bin/python -m pip install -r requirements.txt`; `WORKER_ID=worker-a MODEL_REVISION=714eb0fa89d2f80546fda750413ed43d93601a13 .venv/bin/python -m uvicorn worker:app --port 8001` | `VENV_RC=0`. `PIP_RC=0` (`Successfully installed` … `torch-2.14.0` `transformers-5.17.0` `uvicorn-0.53.0`). Server process 121938. `Uvicorn running on http://127.0.0.1:8001`. Weights loaded 104/104. HF Hub warning: unauthenticated requests. Started 2026-10-15T15:53:30Z. |
| Worker B | PASS | `cd model-workers/model-a`; `WORKER_ID=worker-b MODEL_REVISION=714eb0fa89d2f80546fda750413ed43d93601a13 .venv/bin/python -m uvicorn worker:app --port 8002` | Started 2026-10-15T15:55:49Z after A's `PIP_RC=0`. Server process 122280. `Uvicorn running on http://127.0.0.1:8002`. |
| Backend | PASS | `cd backend/spring-boot`; `PLATFORM_WORKER_URLS=http://localhost:8001,http://localhost:8002 ./mvnw spring-boot:run` | Started 2026-10-15T15:56:21Z. PID 122762. `Started Application in 2.394 seconds`. `worker pool: 2 worker(s), round-robin order [localhost:8001, localhost:8002]`. Empty schema: migrated V1 registry, V2 inference requests, V3 routing decision. `Successfully applied 3 migrations to schema "public", now at version v3 (execution time 00:00.021s)`. Flyway: `Database: jdbc:postgresql://localhost:5432/docs_4e2e (PostgreSQL 16.15)`. Datasource line has product and version only. Tomcat on 8080. |
| Frontend | PASS | `cd frontend`; `npm ci`; `VITE_API_BASE_URL=http://localhost:8080 npm run dev` | `NPM_CI_RC=0`: added 536 packages, audited 537, 0 vulnerabilities. Vite v8.1.5 ready in 1380 ms. Log line: `Port 8080 is in use, trying another one...` then `Local: http://localhost:8081/`. A probe that is not inside the fence: `http://localhost:5173/` connection refused; `http://localhost:8081/` HTTP 200. The fence itself does not name 5173. `docs/DEMO.md` still tells the PowerShell flow to open `http://localhost:5173`. |
| Checks and seed | PASS | `curl -fsS http://localhost:8080/api/health`; `curl -fsS http://localhost:8001/health`; `curl -fsS http://localhost:8002/health`; `python3 scripts/seed_registry.py --base-url http://localhost:8080`; `curl -fsS http://localhost:8080/api/models/sentiment-distilbert/versions`; versioned POST of `{"capability":"TEXT","input":"This movie is wonderful.","model":"sentiment-distilbert","version":"v1"}`; print response; read `requestId`; `curl -fsS` that `/api/requests/{id}`; six more of the same POST | 2026-10-15T15:58:15Z to 15:58:16Z. Wrapper exit 0. Health body `Backend is healthy`. Worker A pid 121938 and worker B pid 122280, both revision `714eb0fa89d2f80546fda750413ed43d93601a13`, `predictionsServed` 0 before the seed. Seed exit 0: created `sentiment-distilbert` and `v1` at that artifact and revision. Versions array: one object, `createdAt` `2026-10-15T15:58:16.201385Z`. First POST `5bb3a507-d6fb-4edc-a4c3-623368174526`, HTTP success, worker-a, `localhost:8001`, POSITIVE score `0.9998793601989746`, `workerInferenceMs` 19.85, `totalLatencyMs` 42, `modelId` `sentiment-distilbert`, `modelVersion` `v1`, `routing.strategy` `VERSION_AWARE`, `chosen` `localhost:8001`. GET of that id: `SUCCEEDED`, `inputChars` 24, `httpStatus` 200, `totalMs` 42, routing equal to the POST. One backend line: `routing decision requestId=5bb3a507-d6fb-4edc-a4c3-623368174526 strategy=VERSION_AWARE ... eligible=2/2 ... chosen=localhost:8001`. Six more, all success, endpoints `localhost:8002`, `localhost:8001`, `localhost:8002`, `localhost:8001`, `localhost:8002`, `localhost:8001`. `chosen` equals `workerEndpoint` on each. Ids: `181ff817-2ce4-40f7-899e-0f7a3072df87`, `aff65a24-6ca3-4fc2-9d06-5881f466b079`, `55997f84-c586-4054-a451-5ea169346ba8`, `3d8d864c-87be-46e2-bdea-9d15872f440f`, `741278f5-5a2f-4151-99bb-2219c05cdc48`, `69dfdebf-3b97-4309-9d50-8ae566d8cd97`. |

Full command output is in `logs/`. Processes were then stopped. Ports 8080, 8001, 8002, and 8081 refused connections. Worker and Spring logs end with shutdown and `UVICORN_RC=143` / `MVNW_RC=143` / `VITE_RC=143`.

The database was left in place. Tables were not dropped.

## Recommendation

Items 1, 2, 3, 4, and 5 are fixed in the text at `4e2e0a0e88038cc4104bf89294853839b3ab303d`. I-3 is still open: the README banner says WORK IN PROGRESS and the status table still says PLANNED. The five new bash fences ran on a fresh PostgreSQL 16 database, two real DistilBERT workers, Spring, and Vite, and each fence's own commands succeeded. Docker was not available and was not run.
