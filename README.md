# Distributed AI Model Serving Platform

An MSCS capstone project: a **general, model-agnostic** platform for deploying, routing, monitoring and scaling multiple AI models across multiple workers.

It is **not** an application for one specific model. Lung-image classification (AlexNet, ResNet50, CNN) is planned as the first demonstration workload, and the current worker serves a text-sentiment model. Any model that can sit behind the worker HTTP contract should be servable.

```
React frontend  →  Spring Boot control plane  →  routing engine  →  Python model workers  →  models
```

**Research question:** does resource- and performance-aware routing improve latency, throughput, utilization and reliability compared with basic (round-robin / least-loaded) routing? This will be answered with measurements, not assumptions.

---

## Current status

Last updated: on branch `sprint2-request-lifecycle` (request lifecycle tracking, not yet independently tested), Oct 13, 2026.

**Sprint mapping (Trello):**
- **Sprint 1 – Foundation**: model selection, Python worker, common contract, worker health, Docker image, local inference. **Open**: the Docker image has not been verified.
- **Sprint 2 – Serving**: Spring Boot serving layer, request tracking, registry/versions, multiple workers. The end-to-end path React → Spring Boot → worker (called "Phase 1" in older notes and DECISIONS.md) is built and reported passing. Merged into `main` after an independent Grok PASS and owner approval: the model registry with versions (`3ace7ad`, tested `f581a15`), two workers with round-robin routing (`6ebec8f`, tested `0a056a9`) and version-aware dispatch (`856caaa`, tested `4351f23`). Persistent request lifecycle tracking is **implemented on branch `sprint2-request-lifecycle` and not yet tested independently** (see "Request lifecycle tracking" below). Detailed routing decision logs are not started.

Neither sprint is signed off.

| Component | Status | Evidence / notes |
|---|---|---|
| Spring Boot backend | **IMPLEMENTED (Phase 1)** | `GET /api/health` is plain-text liveness (`text/plain`, `Backend is healthy`). `POST /api/inference` is the unified inference API, forwarding to one worker. CORS allows only the React dev origin. The worker URL is validated at startup. Grok reported (TEST REPORT round 2, `8bfc496`, Java 21) that `./mvnw -B test` passed 18/18: ApplicationTests 1, InferenceApiTest 9, WorkerDownTest 1, WorkerUrlValidatorTest 7. A blank or invalid `PLATFORM_WORKER_URL` stops startup with exit 1. On `65d404b` (round 1), 11/11 passed via `bash ./mvnw`, because the wrapper was not yet executable. Claude's own sandbox cannot run Maven. |
| React frontend | **IMPLEMENTED (Phase 1)** | The workspace sends text requests to `/api/inference` and shows the real label, confidence, model, worker and latency, or a clear error. The header shows live backend status. The Workers and Monitoring pages still show "Not connected" because their APIs come in later sprints. Evidence for the inference UI: 6 tests passed, plus build and typecheck, and Grok reported (round 2, `8bfc496`) 0 hydration errors over 3 page loads, a real prediction shown in the UI, and a clear error when the worker is down. **Models page (Sprint 2 registry, Grok PASS on `f581a15`):** reads `GET /api/models`, shows "No models registered yet" when the registry is empty, and when the registry is unavailable it shows the static catalog labelled "Example only". Grok saw all three states in headless Chrome. An open detail dialog kept its own label when refreshing in either direction. |
| Python worker `model-a` | **IMPLEMENTED (single model)** | FastAPI + Hugging Face DistilBERT **text sentiment** model. Endpoints: `GET /health` and `POST /predict` with JSON `{"text": ...}`. Returns label, score, model, revision and inference time. Contract tests pass with a stub model (run by Claude). Real DistilBERT inference was **reported by Grok** on `65d404b` (round 1) and `8bfc496` (round 2): `REAL_MODEL=1 pytest` 10 passed, and the worker and Spring return the same `POSITIVE 0.99988`. Logs are attached in the team Slack thread. Claude's sandbox cannot download the model. The worker Docker image is **not verified** (Sprint 1 item, open). |
| AlexNet / ResNet50 / CNN | **NOT IN REPO** | No code, weights, dataset or evaluation scripts committed yet. No accuracy numbers exist. |
| Model registry + versions (PostgreSQL) | **IMPLEMENTED (Sprint 2, Grok PASS on `f581a15`)** | `/api/models` create/list/get and `/api/models/{id}/versions` create/list/get, stored in PostgreSQL through Flyway migration `V1__registry.sql`. Spring tests run on **in-memory H2 (PostgreSQL mode)**, which is not evidence of real PostgreSQL behaviour. Claude checked the migration and constraints directly on PostgreSQL 16.15 with `psql`. Grok's TEST REPORT on `f581a15` (Ubuntu 24.04, Java 21, native PostgreSQL 16.15, empty database) reported PASS:
- `./mvnw -B test` passed 32/32 on H2 (RegistryApi 14, InferenceApi 9, WorkerUrlValidator 7, WorkerDown 1, Application 1).
- Flyway applied V1 once, and the second start reported "No migration necessary".
- 23/23 API checks passed, covering 400, 404 and 409.
- 8 concurrent creates gave one 201 and seven 409, for both models and versions.
- Records were identical after a JVM-only restart and after a PostgreSQL-only restart.
- `registry_persistence_check.py` passed 23/23.
- The inference regression passed 8/8.
- The seed script created on the first run, did nothing on a rerun, and on a conflicting `v1` exited 1 without overwriting. |
| Two workers + round-robin | **MERGED (Sprint 2, Grok PASS on `0a056a9`)** | `PLATFORM_WORKER_URLS` lists the workers, and the backend sends successive valid requests to them in turn (A, B, A, B). There is no failover. Backend tests use fake worker processes; the real two-worker check is `scripts/round_robin_e2e_test.py`. |
| Version-aware dispatch | **MERGED (Sprint 2, Grok PASS on `4351f23`)** | Optional `model`/`version` on `/api/inference`, resolved in the registry and routed only to workers that report that exact revision. Grok reported 22/22 checks, Maven 55/55, `version_dispatch_e2e_test.py` 19/19, and real two-revision routing (see "Version-aware dispatch"). |
| Request lifecycle tracking | **IMPLEMENTED ON BRANCH, NOT YET TESTED** | Every `/api/inference` request is stored in PostgreSQL (`V2__inference_requests.sql`) as RECEIVED → DISPATCHED → SUCCEEDED/FAILED, readable at `/api/requests`. Claude checked the migration and the lifecycle SQL directly on PostgreSQL 16 with `psql`; the Spring tests and `scripts/request_tracking_e2e_test.py` have not been run by Claude (no Maven or real model in its sandbox). |
| Routing engine (health/latency-aware) | PLANNED | Sprint 3 |
| Redis, Kafka | PLANNED | |
| Health monitoring, failure recovery | PLANNED | |
| Docker (multi-service), Kubernetes, autoscaling | PLANNED | `model-a` has a single Dockerfile (build not yet verified). |
| Load testing and evaluation | PLANNED | |

Nothing marked PLANNED exists yet.

---

## Repository layout

```
.
├── backend/spring-boot/      Spring Boot control plane (Java 21, Spring Boot 4.1.1, Maven wrapper)
├── frontend/                 React + Vite + TanStack Router UI (npm)
├── model-workers/
│   └── model-a/              FastAPI worker serving a Hugging Face text-sentiment model
├── scripts/                  End-to-end test, registry DB setup, seed and persistence check
├── DECISIONS.md              Log of technical decisions and why they were made
└── README.md
```

## Prerequisites

| Tool | Version used | Needed for |
|---|---|---|
| Java (JDK) | 21 | backend |
| Node.js / npm | 22 / 10 | frontend |
| Python | 3.12 (matches the worker Dockerfile) | worker |
| PostgreSQL | 15 or newer (checked with 16) | model registry (the backend will not start without it) |
| Internet access | — | Maven Central (backend dependencies), Hugging Face (model download) |

Maven does not need to be installed separately; the project ships the Maven wrapper (`mvnw`).

## Running locally

Run each component in its own terminal.

### 0. Database (once)

The registry needs a PostgreSQL database and role. This script creates them **only if they do not already exist**. It never drops, recreates or changes existing ones.
```bash
sudo -u postgres env PLATFORM_DB_PASSWORD='choose-a-password' bash scripts/setup_registry_db.sh
# optional: PLATFORM_DB_NAME (default platform), PLATFORM_DB_USER (default platform)
```
The backend's Flyway migration creates the tables on first start. Keep the password in your environment; never commit it.

### 1. Backend: http://localhost:8080

```bash
cd backend/spring-boot
export PLATFORM_DB_PASSWORD='the-same-password'   # PowerShell: $env:PLATFORM_DB_PASSWORD='...'
./mvnw spring-boot:run          # Windows: mvnw.cmd spring-boot:run
```

| Variable | Default |
|---|---|
| `PLATFORM_DB_URL` | `jdbc:postgresql://localhost:5432/platform` |
| `PLATFORM_DB_USER` | `platform` |
| `PLATFORM_DB_PASSWORD` | (empty) |
| `PLATFORM_BACKEND_PORT` | `8080` |
| `PLATFORM_WORKER_URL` | `http://localhost:8001` |

At startup the backend logs which database it is using, without the URL or credentials. For example: `registry datasource: product=PostgreSQL version=16.x activeProfiles=[default]`.

Check it:

```bash
curl -i http://localhost:8080/api/health
# HTTP 200, Content-Type: text/plain, body: Backend is healthy
# This only means the backend process is up. It does not check the worker.
```

Run the tests: `./mvnw test`. The tests use an in-memory H2 database (`src/test/resources/config/application.properties`), so PostgreSQL is not needed for them.

The backend reads the worker address from `PLATFORM_WORKER_URL` (default `http://localhost:8001`). If it is blank or not an absolute `http(s)` URL, the backend **refuses to start** with an `Invalid configuration: platform.worker.url ...` message.

### 2. Python worker: http://localhost:8001

```bash
cd model-workers/model-a
python -m venv .venv
source .venv/bin/activate       # Windows: .venv\Scripts\activate
pip install -r requirements.txt
uvicorn worker:app --port 8001
```

The first start downloads the DistilBERT model from Hugging Face, so it needs internet access and can take a minute.

Check it:

```bash
curl http://localhost:8001/health
curl -X POST http://localhost:8001/predict -H "Content-Type: application/json" -d '{"text":"I love this"}'
```

Worker tests:
```bash
pip install -r requirements-dev.txt
pytest                 # contract tests with a stub model (fast, offline)
REAL_MODEL=1 pytest    # same tests and more, against the real model (PowerShell: $env:REAL_MODEL=1; pytest)
```

### 2b. Two workers (Sprint 2, round-robin)

Run the same worker code twice, as two separate processes, each with its own port and id:
```bash
cd model-workers/model-a
WORKER_ID=worker-a uvicorn worker:app --port 8001    # terminal 1
WORKER_ID=worker-b uvicorn worker:app --port 8002    # terminal 2
# PowerShell: $env:WORKER_ID="worker-a"; uvicorn worker:app --port 8001
```
Then start the backend with both URLs. The list order is the round-robin order:
```bash
cd backend/spring-boot
PLATFORM_WORKER_URLS=http://localhost:8001,http://localhost:8002 ./mvnw spring-boot:run
# PowerShell: $env:PLATFORM_WORKER_URLS="http://localhost:8001,http://localhost:8002"; .\mvnw.cmd spring-boot:run
```
At startup the backend logs `worker pool: 2 worker(s), round-robin order [localhost:8001, localhost:8002]`.

How it behaves:
- **Order:** each valid request goes to the next worker in the list: A, B, A, B. The counter is thread-safe, so concurrent requests are also split evenly. Requests rejected with 400 or 422 do not take a turn.
- **Response:** shows both which process answered (`worker`, the worker's own `WORKER_ID`) and where the backend sent the request (`workerEndpoint`, host:port only).
- **No failover:** if the chosen worker is down or times out (2 s to connect, 30 s to answer), that request fails with `503 WORKER_UNAVAILABLE`. The message names the endpoint that was tried. The next request goes to the next worker. Failover is not implemented.
- **Configuration checks:** the backend refuses to start if the list is empty, has an empty entry, contains an invalid URL, contains a user name or password, or lists the same worker twice. Two different host names for the same machine (`localhost` and `127.0.0.1`) cannot be detected.
- **Unchanged default:** without `PLATFORM_WORKER_URLS`, the single `PLATFORM_WORKER_URL` is used exactly as before.

Each worker's `/health` reports its `pid` and `predictionsServed`, the number of successful predictions since that process started. `python scripts/round_robin_e2e_test.py` uses these to check that:
- the two workers are different processes;
- 8 requests alternate between them;
- each worker served exactly half.

### 2c. Version-aware dispatch (Sprint 2)

An inference request can name a registered model, and optionally a version:
```json
{ "capability": "TEXT", "input": "I love this", "model": "sentiment-distilbert", "version": "v1" }
```
- **No `model`:** the request behaves exactly as before. It goes round-robin over all workers, and the registry is not consulted.
- **`model` without `version`, or `"version": "latest"`:** uses the most recently registered version of that model.
- **`version` without `model`:** rejected with 400.

The backend resolves the version in the PostgreSQL registry, which gives an artifact and a 40-hex revision. It then sends the request only to workers whose own `GET /health` reports exactly that artifact and revision, round-robin among them. The worker's `/predict` reply must report the same artifact and revision. If it does not, the request fails with `502 WORKER_MODEL_MISMATCH`.

**No substitution:** if no worker runs the requested revision, the answer is `422 NO_ELIGIBLE_WORKER`. The backend never falls back to another version.

Worker identities are cached for 30 s (`platform.worker.identity-max-age-ms`) and probed with a 2 s timeout (`platform.worker.probe-timeout-ms`).

How worker availability affects versioned requests:
- **Unknown identity:** a worker whose identity is unknown is not used for versioned requests (it fails closed). This covers a worker that never answered `/health`, or one that reports no revision.
- **Known, then down:** a worker that was known and then went down keeps its last known identity. Requests routed to it still get `503`. There is no failover.

The response adds `modelId` and `modelVersion`, the registry label that was served. Both are `null` when no model was named.

| HTTP | Code | When |
|---|---|---|
| 400 | `INVALID_REQUEST` | `version` without `model`; malformed model id or version label |
| 404 | `MODEL_NOT_FOUND` / `VERSION_NOT_FOUND` | Not registered. A model with no versions gives `VERSION_NOT_FOUND`. |
| 422 | `CAPABILITY_MISMATCH` | The requested capability (not `AUTO`) differs from the model's capability |
| 422 | `NO_ELIGIBLE_WORKER` | No worker serves that capability, or that exact artifact and revision |
| 503 | `REGISTRY_UNAVAILABLE` | The registry database cannot be read |
| 502 | `WORKER_MODEL_MISMATCH` | The worker answered with a different artifact or revision |

The label `latest` is reserved and cannot be registered as a version.

**What is real today:**
- Both workers load the same DistilBERT revision, so only one registered version can actually be served.
- Any version with another revision fails closed with 422.
- To serve a genuinely different revision, start a worker with `MODEL_REVISION=<hugging face commit>`. That revision is passed to `pipeline(revision=...)`, and the worker reports the commit it actually loaded. Then register that commit as another version.
- Two-revision serving was demonstrated by Grok on `4351f23` (reported): worker B was started with `MODEL_REVISION` set to commit `da4550f9…` while A kept `714eb0fa…`; v1 requests went only to A and v2 only to B, including 40 concurrent requests per version with 0 errors.

`python scripts/version_dispatch_e2e_test.py` checks this on the real stack. It registers a model whose `v1` has the workers' real revision and whose `v2` has a synthetic revision that no worker runs. It then checks:
- the A/B order for `v1`;
- `latest` (`v2`) returns 422, and no worker runs;
- the 404, 400 and 422 error cases;
- a request without a model still works.

### 2d. Request lifecycle tracking (Sprint 2)

Every `POST /api/inference` request is stored in the PostgreSQL table `inference_requests` (Flyway migration `V2__inference_requests.sql`), keyed by the `requestId` the API already returns:

| Status | Meaning |
|---|---|
| `RECEIVED` | The request arrived (row created). |
| `DISPATCHED` | A worker was chosen and the request was sent to it. |
| `SUCCEEDED` | 200 was returned. |
| `FAILED` | Any error was returned; `httpStatus` and `errorCode` are the exact values the client got. |

A request rejected before a worker is chosen (400, 404, 422, `REGISTRY_UNAVAILABLE`) goes straight from `RECEIVED` to `FAILED` and has no `dispatchedAt`. A row left in `RECEIVED` or `DISPATCHED` means the backend stopped before answering. Each step only moves a row forward, so a late write cannot rewind a finished request.

Each row stores: requested capability, model and version; the input **length** (`inputChars`) but **never the input text**; the resolved version, artifact and revision (versioned requests); the worker endpoint it was sent to and the worker id that answered; `httpStatus`, `errorCode`, `totalMs`; and `receivedAt`, `dispatchedAt`, `completedAt` (database clock, UTC).

| Method and path | Success | Errors |
|---|---|---|
| `GET /api/requests/{requestId}` | 200 + one request | 400 `INVALID_REQUEST` (not a lowercase UUID), 404 `REQUEST_NOT_FOUND` |
| `GET /api/requests?limit=N` | 200 + array, newest first (`N` 1–100, default 20) | 400 `INVALID_REQUEST` |
| either, database unreadable | | 503 `TRACKING_UNAVAILABLE` |

```bash
curl -s localhost:8080/api/requests?limit=5
curl -s localhost:8080/api/requests/<requestId from an /api/inference response>
```

**Best-effort writes:** if the tracking table cannot be written, the failure is logged as `request tracking failed requestId=... step=...` and the inference request is answered exactly as it would be without tracking. A request without `model` therefore still works while PostgreSQL is down, but that request is not recorded. To avoid slowing every request while the database is down, a failed write pauses tracking writes for 10 s (`platform.tracking.pause-after-failure-ms`), and a database connection wait is capped at 2 s (`PLATFORM_DB_CONNECT_TIMEOUT_MS`, default 2000; this also applies to the registry). A body that is not valid JSON is rejected by Spring before the controller runs and is not tracked.

`python scripts/request_tracking_e2e_test.py` checks tracking on the real stack: success, invalid input, unknown model, list order and limits, concurrent requests, and read errors. For persistence across a backend restart, run it with `--save /tmp/tracked.json`, restart the backend on the same database, then run it with `--verify /tmp/tracked.json`.

### 3. Frontend: http://localhost:5173

```bash
cd frontend
npm ci
cp .env.example .env.local      # sets VITE_API_BASE_URL=http://localhost:8080 (Windows: copy)
npm run dev
```

Other commands:
- `npm test` — unit tests

Registry seed-script tests: `python -m pytest scripts/tests/test_seed_registry.py` (they use an in-memory fake of the API, not the backend).
- `npm run lint` — lint
- `npm run build` — production build

The UI reads the backend address from `VITE_API_BASE_URL`. When that variable is unset, every page shows "Not connected".

## Unified inference API (Phase 1)

`POST /api/inference` (Spring Boot)

Request:
```json
{ "capability": "TEXT", "input": "I love this" }
```
- `capability` is `TEXT` or `AUTO`. All configured workers serve `TEXT`.

Success (`200`):
```json
{ "status": "success", "requestId": "…", "capability": "TEXT", "worker": "worker-a",
  "workerEndpoint": "localhost:8001",
  "model": "distilbert-base-uncased-finetuned-sst-2-english", "version": "<hf revision or null>",
  "task": "sentiment-analysis", "prediction": { "label": "POSITIVE", "score": 0.9998 },
  "workerInferenceMs": 14.2, "totalLatencyMs": 31 }
```
The values above are illustrative of the shape only. They are not measured results.

Errors use the shape `{ "status": "error", "error": "<CODE>", "message": "…", "requestId": "…" }`:

| HTTP | Code | Meaning |
|---|---|---|
| 400 | `INVALID_REQUEST` | `input` is missing, blank, or longer than 10,000 characters |
| 422 | `NO_ELIGIBLE_WORKER` | No worker serves the requested capability (e.g. `IMAGE`) |
| 503 | `WORKER_UNAVAILABLE` | The chosen worker is down or timed out (no retry on another worker) |
| 502 | `WORKER_ERROR` | The chosen worker answered with an error or an unreadable response |

The worker's own validation errors (`422` from `POST /predict`) look like `{"detail": [{"loc": ["body", "text"], "msg": "...", "type": "..."}]}`. They do not echo the rejected text back.

### Phase 1 end-to-end test

With the backend running and the worker dependencies installed, run this from the repository root:
```bash
python scripts/phase1_e2e_test.py
```
The script starts the real worker, then runs tests A–D and F:
- A–D: health checks, direct inference, inference through Spring Boot, validation, and CORS.
- F: stops the worker, checks that the backend returns a controlled 503, restarts the worker, and checks recovery.

It writes `phase1_e2e_results.json` and prints the steps for the manual UI test (Test E).

## Model registry API (Sprint 2)

The registry records which models and versions exist. It does not load, start or route to them. "Registered" does not mean deployed or running.

| Method and path | Success | Errors |
|---|---|---|
| `POST /api/models` | 201 + model | 400 `INVALID_REQUEST`, 409 `MODEL_ALREADY_EXISTS` |
| `GET /api/models` | 200 + array (may be empty) | |
| `GET /api/models/{id}` | 200 + model with `versionCount` and `latestVersion` | 404 `MODEL_NOT_FOUND` |
| `POST /api/models/{id}/versions` | 201 + version | 400, 404 `MODEL_NOT_FOUND`, 409 `VERSION_ALREADY_EXISTS`, 409 `DUPLICATE_ARTIFACT` |
| `GET /api/models/{id}/versions` | 200 + array, oldest first | 404 `MODEL_NOT_FOUND` |
| `GET /api/models/{id}/versions/{version}` | 200 + version | 404 `MODEL_NOT_FOUND`, 404 `VERSION_NOT_FOUND` |

Errors use the same shape as the inference API: `{"status":"error","error":CODE,"message":...,"requestId":...}`.

```bash
curl -X POST localhost:8080/api/models -H "Content-Type: application/json" \
  -d '{"id":"sentiment-distilbert","name":"DistilBERT SST-2","task":"sentiment-analysis","capability":"TEXT"}'
curl -X POST localhost:8080/api/models/sentiment-distilbert/versions -H "Content-Type: application/json" \
  -d '{"version":"v1","artifact":"distilbert-base-uncased-finetuned-sst-2-english","artifactRevision":"<full 40-hex commit>","framework":"transformers"}'
```

**Rules:**
- All strings are trimmed before they are checked.
- `id` must be a lowercase slug of 2–63 characters: `a-z`, `0-9` and `-`.
- `capability` must be `TEXT`, `CODE`, `IMAGE` or `DOCUMENT`.
- `version` is a label of 1–64 characters: letters, digits, `.`, `_` and `-`. No `/`, so it is safe in a URL.
- `artifactRevision` must be the **full 40-character hex commit**. Abbreviated hashes are rejected, and uppercase is stored as lowercase. A registration only records this metadata. It does not prove the artifact was downloaded or ever run.
- The same artifact and revision cannot be registered twice for one model under different labels. That would be an alias, not a new version.
- `latestVersion`, and `version` in the list, is the **most recently registered** version: newest `created_at`, ties broken by id. It is not semantic-version order, and it does not mean the version is active or deployed.
- Uniqueness is enforced by PostgreSQL constraints. Two simultaneous identical requests therefore give one 201 and one 409.

**Seed the real model:** with the worker and backend running, run `python scripts/seed_registry.py`. It reads the revision from a real `/api/inference` response, so the revision is never typed by hand. Re-running is safe: if the model or `v1` already exists with the same values the script reports it and exits 0. If `v1` already points to a different artifact or revision, it reports the mismatch, changes nothing and exits 1.

**Persistence check on real PostgreSQL:** use a dedicated test database. The script starts the backend itself, creates records, restarts only the process it started, and compares the records. It never deletes data.
```bash
cd backend/spring-boot && ./mvnw -B -DskipTests package && cd ../..
export PLATFORM_DB_URL=jdbc:postgresql://localhost:5432/registry_check PLATFORM_DB_USER=registry_check PLATFORM_DB_PASSWORD=...
python scripts/registry_persistence_check.py --port 8080 \
  --start-cmd "java -jar backend/spring-boot/target/spring-boot-0.0.1-SNAPSHOT.jar"
```

**Models page:**
- When the registry answers, the page shows the registered models, or "No models registered yet" if there are none.
- If the registry cannot be read, the page says so and shows the old catalog marked **"Example only"**. Example rows are never shown as registered models.

## Development workflow

- `main` only receives reviewed work.
- Each phase is built on its own branch, named `phase-<N>-<topic>`.
- Each branch is independently tested before merging.
- Technical decisions are recorded in [DECISIONS.md](DECISIONS.md).
- Never commit secrets, `.env` files, `node_modules`, build output, or model weights.

## Known issues

- The frontend build still depends on `@lovable.dev/vite-tanstack-config`, the generator's shared Vite config. It works, but it should be replaced with an explicit Vite config in a later cleanup.
- The frontend's production build targets Cloudflare Workers by default (via nitro). This is irrelevant for local development.
- The welcome-page network graphic shows a static "Not connected" label even when the header shows "Backend connected". This is cosmetic, not a hydration error, and is noted for Sprint 2.
- The worker's `/health` only reports that the process is up. The model loads at startup, and the process exits if loading fails. Deeper health checks (resource usage, readiness under load) belong to the health-monitoring phase.
- The Workers and Monitoring pages call `/api/workers` and `/api/metrics`, which do not exist yet. They fall back to "Not connected", and the browser console shows 404s for those calls. (`/api/models` exists from Sprint 2.)
- The backend now needs a reachable PostgreSQL database to start, because Flyway runs at startup.
