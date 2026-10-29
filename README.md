# Distributed AI Model Serving Platform

An MSCS capstone project: a **general, model-agnostic** platform for deploying, routing, monitoring and scaling multiple AI models across multiple workers.

It is **not** an application for one specific model. Lung-image classification (AlexNet, ResNet50, CNN) is planned as the first demonstration workload, and the current worker serves a text-sentiment model. Any model that can sit behind the worker HTTP contract should be servable.

```
React frontend  →  Spring Boot control plane  →  routing engine  →  Python model workers  →  models
```

**Research question:** does resource- and performance-aware routing improve latency, throughput, utilization and reliability compared with basic (round-robin / least-loaded) routing? This will be answered with measurements, not assumptions.

---

## Current status

Last updated: Oct 29, 2026. Sprint 3 is closed (final docs merged as `2aca70a`; retrospective published Oct 24). Sprint 4 bounded failover code is merged via PR #12 (`8044116`, reviewed/tested `58db847`); its demo/screenshots/retrospective closure remains open. Sprint 3 routing policies merged: least-in-flight as `a54aba5` (tested `b75e0ad`) and health/latency as `4a2c343` (tested `ed28851`); routing docs merged as `39e59e7`. Sprint 2 routing decisions: merge `1c1d932eba0dd75a8920186d4571cce0eeaba470`, tested `2f731ba2991cbcfbb68191e0c620f3b89da1b238`.

**Sprint mapping (Trello):**
- **Sprint 1 – Foundation**: model selection, Python worker, common contract, worker health, Docker image, local inference. **Open**: the worker image build and `/health` passed once in GitHub Actions on 2026-10-14 (see Docker below); a current rebuild is not verified and the image is not run as part of the full stack.
- **Sprint 2 – Serving**: Spring Boot serving layer, request tracking, registry/versions, multiple workers. The end-to-end path React → Spring Boot → worker (called "Phase 1" in older notes and DECISIONS.md) is built and reported passing. Merged into `main` after an independent Grok PASS and owner approval: the model registry with versions (`3ace7ad`, tested `f581a15`), two workers with round-robin routing (`6ebec8f`, tested `0a056a9`) and version-aware dispatch (`856caaa`, tested `4351f23`). Persistent request lifecycle tracking is merged as `87eaf0f`, and detailed routing decisions are merged as `1c1d932`. Both received independent Grok testing and Codex review of source and supplied runtime evidence. See [Sprint 2 closeout](docs/SPRINT2_CLOSEOUT.md) and [demo runbook](docs/DEMO.md).
- **Sprint 3 – Intelligent routing (closed)**: two opt-in routing policies, selected at startup with `PLATFORM_ROUTING_POLICY`; round-robin stays the default. `LEAST_IN_FLIGHT` (merge `a54aba5`, tested `b75e0ad`) and `HEALTH_LATENCY` (merge `4a2c343`, tested `ed28851`). Both received independent Grok testing on real PostgreSQL and real DistilBERT workers, and Claude/Codex source review. Evidence and limits: [Sprint 3 routing](docs/SPRINT3_ROUTING.md).
  - *What was tested:* each slice on its own tested head. The merges that followed changed documentation only, which was checked by diff. The merged `main` has not had its own full end-to-end rerun.
  - *Where it is visible:* the new `policy`, `inFlight` and `health` fields appear in the API response, `GET /api/requests/{id}` and the routing log line. **The frontend does not display them yet** (API-only for now).
  - *What is not claimed:* the RR/LIF/HEALTH_LATENCY comparisons are single runs that show behaviour. There is no repeated performance evaluation and no general speed-up claim; that belongs to Sprint 6.

Sprint 3 is closed; its Trello card is Done and marked complete. Formal Sprint 1/2 signoff is not inferred from the Phase 1 development milestone.

**Docker — PARTLY VERIFIED:** the `model-a` worker image build and its `/health` check passed in GitHub Actions on 2026-10-14 (PR #3, run 38541943495; head `273e185`, tested merge ref `5c0219f`; log confirmed by Grok, see D-048). No current rebuild evidence has been reviewed. `/predict` inside the container, the backend, PostgreSQL and any multi-service setup are **not** containerised or verified. Demos use native processes.

Presentation: [full speaking script, STAR explanation and simple example](docs/SPRINT2_PRESENTATION.md).

| Component | Status | Evidence / notes |
|---|---|---|
| Spring Boot backend | **IMPLEMENTED (Phase 1)** | `GET /api/health` is plain-text liveness (`text/plain`, `Backend is healthy`). `POST /api/inference` is the unified inference API, forwarding to one worker. CORS permits the configured React dev origins (by default http://localhost:5173 and http://127.0.0.1:5173). The worker URL is validated at startup. Grok reported (TEST REPORT round 2, `8bfc496`, Java 21) that `./mvnw -B test` passed 18/18: ApplicationTests 1, InferenceApiTest 9, WorkerDownTest 1, WorkerUrlValidatorTest 7. A blank or invalid `PLATFORM_WORKER_URL` stops startup with exit 1. On `65d404b` (round 1), 11/11 passed via `bash ./mvnw`, because the wrapper was not yet executable. Claude's own sandbox cannot run Maven. |
| React frontend | **IMPLEMENTED (Phase 1)** | The workspace sends text requests to `/api/inference` and shows the real label, confidence, model, worker and latency, or a clear error. The header shows live backend status. The Workers and Monitoring pages still show "Not connected" because their APIs come in later sprints. Evidence for the inference UI: 6 tests passed, plus build and typecheck, and Grok reported (round 2, `8bfc496`) 0 hydration errors over 3 page loads, a real prediction shown in the UI, and a clear error when the worker is down. **Models page (Sprint 2 registry, Grok PASS on `f581a15`):** reads `GET /api/models`, shows "No models registered yet" when the registry is empty, and when the registry is unavailable it shows the static catalog labelled "Example only". Grok saw all three states in headless Chrome. An open detail dialog kept its own label when refreshing in either direction. |
| Python worker `model-a` | **IMPLEMENTED (single model)** | FastAPI + Hugging Face DistilBERT **text sentiment** model. Endpoints: `GET /health` and `POST /predict` with JSON `{"text": ...}`. Returns label, score, model, revision and inference time. Contract tests pass with a stub model (run by Claude). Real DistilBERT inference was **reported by Grok** on `65d404b` (round 1) and `8bfc496` (round 2): `REAL_MODEL=1 pytest` 10 passed, and the worker and Spring return the same `POSITIVE 0.99988`. Logs are attached in the team Slack thread. Claude's sandbox cannot download the model. The worker Docker image build + `/health` passed once in GitHub Actions on 2026-10-14 (head `273e185`, tested merge ref `5c0219f`; D-048); a current rebuild and `/predict` in the container are **not verified**. |
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
| Request lifecycle tracking | **MERGED (Sprint 2, Grok PASS on `e70b733`, merge `87eaf0f`)** | Every `/api/inference` request is stored in PostgreSQL (`V2__inference_requests.sql`) as RECEIVED → DISPATCHED → SUCCEEDED/FAILED, readable at `/api/requests`. Grok reported 29/29 runnable checks on `e70b733` (Maven 74/74, real pgjdbc lock-hold cancel at ~2.06 s, outage and recovery); Docker was not available on the test VM. |
| Routing decisions | **MERGED (Sprint 2, tested `2f731ba`, merge `1c1d932`)** | Codex reviewed Grok's supplied native PostgreSQL/real-worker evidence: routing response/log/database consistency, V2→V3 preservation, tracking regressions, Maven 86/86 (H2/fakes), frontend 12/12 and real UI DOM checks. Docker remains deferred/unverified. The Sprint 2 retrospective package (slides and PDF) has been produced from user-supplied local demo screenshots, whose checkout SHA is unknown; that is a delivered package, not formal sprint sign-off. |
| Routing policies (Sprint 3) | **MERGED, opt-in (`ROUND_ROBIN` default)** | `LEAST_IN_FLIGHT`: fewest requests in progress from this backend, ties rotated (merge `a54aba5`, tested `b75e0ad`). `HEALTH_LATENCY`: skips workers whose background `/health` checks fail and picks the lowest `(inFlight + 1) × estimated latency` (merge `4a2c343`, tested `ed28851`). Both: per backend instance only, exact model version only; automatic failover is a separate default-disabled Sprint 4 option. Single-run measurements and known limits are in [docs/SPRINT3_ROUTING.md](docs/SPRINT3_ROUTING.md). The UI does not show the new fields yet. |
| Redis, Kafka | PLANNED | |
| Bounded failover (Sprint 4 code slice) | **MERGED, default disabled** | At most two distinct eligible worker attempts with a shared dispatch budget, conservative retry boundary and scoped reservation release. Exact version preserved. Claude reviewed and Grok independently tested `58db847` (158 tests); merge `8044116` has the same tree. See [contract](docs/SPRINT4_FAILOVER.md) and [build report](docs/SPRINT4_BUILD_REPORT.md). Formal sprint closure remains open. |
| Monitoring dashboard (Sprint 5) | PLANNED | No live monitoring or failover UI. The frontend "Failure Recovery" and "Intelligent Routing" pages remain static target-architecture illustrations labelled "Not connected". |
| Docker | **PARTLY VERIFIED (worker image only)** | `model-a` image build + `/health` passed in GitHub Actions run 38541943495 on 2026-10-14 (head `273e185`, tested merge ref `5c0219f`; PR #3, not yet merged). No current rebuild evidence reviewed. No `/predict` in the container, no backend/PostgreSQL images, no multi-service setup. Native processes are used for the demo. |
| Kubernetes, autoscaling | PLANNED | Not implemented. |
| Load testing and evaluation | PLANNED | Sprint 6. Sprint 3 has single-run behavioural comparisons only (see Sprint 3 routing doc). |

PLANNED items are not implemented. Docker is verified only for one historical CI worker image build and `/health` (2026-10-14).

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

The application's datasource summary omits the URL and credentials, but Flyway startup logs can include the JDBC database URL. Keep credentials out of the URL and redact host/database details before sharing logs. For example: `registry datasource: product=PostgreSQL version=16.x activeProfiles=[default]`.

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
- **Order:** each valid request goes to the next worker in the list: A, B, A, B. The counter is thread-safe, so concurrent requests are also split evenly. Requests rejected before routing with 400, 404 or 422 do not take a turn.
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

A request rejected before a worker is chosen (400, 404, 422, `REGISTRY_UNAVAILABLE`) goes straight from `RECEIVED` to `FAILED` and has no `dispatchedAt`. A row left in `RECEIVED` or `DISPATCHED` means tracking is incomplete for that request: a tracking write was skipped or failed, or the backend stopped before answering. It is not by itself proof of a crash. Each step only moves a row forward, so a late write cannot rewind a finished request.

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

**Best-effort writes:** if the tracking table cannot be written, the failure is logged as `request tracking failed requestId=... step=...` and the inference request is answered exactly as it would be without tracking. A request without `model` therefore still works while PostgreSQL is down, but that request is not recorded. Every tracking statement is cancelled after 2 s (`platform.tracking.statement-timeout-seconds`), including one blocked by a lock on the table, and a database connection wait is capped at 2 s (`PLATFORM_DB_CONNECT_TIMEOUT_MS`, default 2000; this also applies to the registry). After a failed write, tracking skips new writes for 10 s (`platform.tracking.pause-after-failure-ms`), then lets one write at a time probe the database. Each tracking write attempt is bounded by about 4 s (connection wait + statement timeout); this is a per-attempt budget, not a per-request total. Requests whose tracking writes had already started when the database failed each pay that budget once. After a request's first failed or skipped tracking write, no further tracking attempts are made for that request (worker time is not tracking overhead and is not counted). A body that is not valid JSON is rejected by Spring before the controller runs and is not tracked.

`python scripts/request_tracking_e2e_test.py` checks tracking on the real stack: success, invalid input, unknown model, list order and limits, concurrent requests, and read errors. For persistence across a backend restart, run it with `--save /tmp/tracked.json`, restart the backend on the same database, then run it with `--verify /tmp/tracked.json`.

### 2e. Routing decisions (Sprint 2 #5)

Every `POST /api/inference` request gets one **routing decision** that explains where it went and why. It is built from the same worker list and the same identity reads the router used (nothing is probed again just to explain it, and the round-robin counter moves only once).

| Field | Meaning |
|---|---|
| `strategy` | `ROUND_ROBIN_ALL` (no `model`), `VERSION_AWARE` (`model` given), `NOT_ROUTED` (rejected before any worker was considered) |
| `target` | `artifact@revision` a versioned request needs (else `null`) |
| `poolSize`, `eligibleCount` | configured workers, and how many could serve this request |
| `candidates[]` | every configured worker: `endpoint`, `eligible`, `note`, `served` |
| `chosen` | `host:port` the request was sent to (`null` if none) |
| `reason` | one short sentence, or the error code for `NOT_ROUTED` |

Candidate `note`: `ANY_WORKER` (no model requested, identity not checked), `ELIGIBLE` (its `/health` reports exactly the target), `SERVES_OTHER` (reports another artifact/revision, shown in `served`), `IDENTITY_UNKNOWN` (identity not known, so not used: fail closed). **Eligible means the right model identity, not "up right now"**: a chosen worker can still be down, the request then fails with 503 and there is no failover. `NOT_ROUTED` decisions have no candidates, because none were evaluated.

Where it appears:

- **Log:** one line per request, e.g. `routing decision requestId=... strategy=VERSION_AWARE target=art@714eb0fa eligible=1/2 candidates=[127.0.0.1:8001:ELIGIBLE, 127.0.0.1:8002:SERVES_OTHER(art@3f5a...)] chosen=127.0.0.1:8001 reason="..."`. The input text is never logged. Worker-reported identities are not trusted: control characters (CR, LF, ...) are escaped, so one decision is always one log line.
- **Database:** column `routing_decision` (JSON text, Flyway `V3__routing_decision.sql`), written in the same best-effort tracking updates as §2d, so it follows the same timeouts, pause and per-request suppression. Rows from before V3, or whose write was skipped, have `null`.
- **API:** `GET /api/requests/{id}` returns it as `routing`; a successful `POST /api/inference` also returns it as `routing`. Error responses do not carry it; look the request up by its `requestId`.
- **UI:** the "Routing details" badge says "Backend decision" (with eligible/total workers and the reason), "Decision unavailable" (the backend answered but sent no decision, e.g. an older backend), "Backend error response" (the backend answered with an error), "Not sent" (rejected in the browser, e.g. an attachment) or "Not connected" (no backend reached). While a new request is in flight the previous answer is labelled "Previous request" and its routing rows are hidden; the router panel's "Eligible workers" is blank until the new decision arrives. The panel header shows backend reachability only; worker eligibility is per request.

A body that is not valid JSON is rejected by Spring before the controller runs, so it gets no routing decision (same limitation as §2d).

`python scripts/routing_decision_e2e_test.py [--model sent --version v1] [--log backend.log]` checks this on the real stack: round-robin and versioned decisions, rejected requests, response == stored decision, and (with `--log`) one matching log line per request.

### 3. Frontend: http://localhost:5173

```bash
cd frontend
npm ci
cp .env.example .env.local      # sets VITE_API_BASE_URL=http://localhost:8080 (Windows: copy)
npm run dev -- --port 5173 --strictPort
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
  "workerInferenceMs": 14.2, "totalLatencyMs": 31,
  "modelId": null, "modelVersion": null,
  "routing": { "strategy": "ROUND_ROBIN_ALL", "target": null,
    "poolSize": 1, "eligibleCount": 1,
    "candidates": [{ "endpoint": "localhost:8001", "eligible": true, "note": "ANY_WORKER", "served": null }],
    "chosen": "localhost:8001", "reason": "round-robin over 1 eligible" } }
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
