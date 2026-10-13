# Distributed AI Model Serving Platform

An MSCS capstone project: a **general, model-agnostic** platform for deploying, routing, monitoring and scaling multiple AI models across multiple workers.

It is **not** an application for one specific model. Lung-image classification (AlexNet, ResNet50, CNN) is planned as the first demonstration workload, and the current worker serves a text-sentiment model. Any model that can sit behind the worker HTTP contract should be servable.

```
React frontend  →  Spring Boot control plane  →  routing engine  →  Python model workers  →  models
```

**Research question:** does resource- and performance-aware routing improve latency, throughput, utilization and reliability compared with basic (round-robin / least-loaded) routing? This will be answered with measurements, not assumptions.

---

## Current status

Last updated: after Grok's TEST REPORT round 2 on `8bfc496` (Oct 12, 2026).

**Sprint mapping (Trello):**
- **Sprint 1 – Foundation**: model selection, Python worker, common contract, worker health, Docker image, local inference. **Open**: the Docker image has not been verified.
- **Sprint 2 – Serving**: Spring Boot serving layer, request tracking, registry/versions, multiple workers. **Early**: the end-to-end path React → Spring Boot → worker (branch `phase-1-end-to-end-inference`, called "Phase 1" in older notes and DECISIONS.md) is built and reported passing. Registry, versions, multiple workers and request tracking are not started.

Neither sprint is signed off.

| Component | Status | Evidence / notes |
|---|---|---|
| Spring Boot backend | **IMPLEMENTED (Phase 1)** | `GET /api/health` is plain-text liveness (`text/plain`, `Backend is healthy`). `POST /api/inference` is the unified inference API, forwarding to one worker. CORS allows only the React dev origin. The worker URL is validated at startup. Grok reported (TEST REPORT round 2, `8bfc496`, Java 21) that `./mvnw -B test` passed 18/18: ApplicationTests 1, InferenceApiTest 9, WorkerDownTest 1, WorkerUrlValidatorTest 7. A blank or invalid `PLATFORM_WORKER_URL` stops startup with exit 1. On `65d404b` (round 1), 11/11 passed via `bash ./mvnw`, because the wrapper was not yet executable. Claude's own sandbox cannot run Maven. |
| React frontend | **IMPLEMENTED (Phase 1)** | The workspace sends text requests to `/api/inference` and shows the real label, confidence, model, worker and latency, or a clear error. The header shows live backend status. The Models, Workers and Monitoring pages still show "Not connected" because their APIs come in later phases. 6 tests pass, and build and typecheck pass. Grok reported (round 2) 0 hydration errors over 3 page loads, a real prediction shown in the UI, and a clear error when the worker is down. |
| Python worker `model-a` | **IMPLEMENTED (single model)** | FastAPI + Hugging Face DistilBERT **text sentiment** model. Endpoints: `GET /health` and `POST /predict` with JSON `{"text": ...}`. Returns label, score, model, revision and inference time. Contract tests pass with a stub model (run by Claude). Real DistilBERT inference was **reported by Grok** on `65d404b` (round 1) and `8bfc496` (round 2): `REAL_MODEL=1 pytest` 10 passed, and the worker and Spring return the same `POSITIVE 0.99988`. Logs are attached in the team Slack thread. Claude's sandbox cannot download the model. The worker Docker image is **not verified** (Sprint 1 item, open). |
| AlexNet / ResNet50 / CNN | **NOT IN REPO** | No code, weights, dataset or evaluation scripts committed yet. No accuracy numbers exist. |
| Multiple workers, registry, routing engine | PLANNED | |
| PostgreSQL, Redis, Kafka | PLANNED | |
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
├── DECISIONS.md              Log of technical decisions and why they were made
└── README.md
```

## Prerequisites

| Tool | Version used | Needed for |
|---|---|---|
| Java (JDK) | 21 | backend |
| Node.js / npm | 22 / 10 | frontend |
| Python | 3.12 (matches the worker Dockerfile) | worker |
| Internet access | — | Maven Central (backend dependencies), Hugging Face (model download) |

Maven does not need to be installed separately; the project ships the Maven wrapper (`mvnw`).

## Running locally

Run each component in its own terminal.

### 1. Backend: http://localhost:8080

```bash
cd backend/spring-boot
./mvnw spring-boot:run          # Windows: mvnw.cmd spring-boot:run
```

Check it:

```bash
curl -i http://localhost:8080/api/health
# HTTP 200, Content-Type: text/plain, body: Backend is healthy
# This only means the backend process is up. It does not check the worker.
```

Run the tests: `./mvnw test`

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

### 3. Frontend: http://localhost:5173

```bash
cd frontend
npm ci
cp .env.example .env.local      # sets VITE_API_BASE_URL=http://localhost:8080 (Windows: copy)
npm run dev
```

Other commands:
- `npm test` — unit tests
- `npm run lint` — lint
- `npm run build` — production build

The UI reads the backend address from `VITE_API_BASE_URL`. When that variable is unset, every page shows "Not connected".

## Unified inference API (Phase 1)

`POST /api/inference` (Spring Boot)

Request:
```json
{ "capability": "TEXT", "input": "I love this" }
```
- `capability` is `TEXT` or `AUTO`. In Phase 1 only one text worker exists.

Success (`200`):
```json
{ "status": "success", "requestId": "…", "capability": "TEXT", "worker": "model-a",
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
| 503 | `WORKER_UNAVAILABLE` | The worker is down or timed out |
| 502 | `WORKER_ERROR` | The worker answered with an error or an unreadable response |

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
- The Models, Workers and Monitoring pages call `/api/models`, `/api/workers` and `/api/metrics`, which do not exist yet. They fall back to "Not connected", and the browser console shows 404s for those calls.
