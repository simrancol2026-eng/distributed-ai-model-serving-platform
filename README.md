# Distributed AI Model Serving Platform

An MSCS capstone project: a **general, model-agnostic** platform for deploying, routing, monitoring and scaling multiple AI models across multiple workers.

It is **not** an application for one specific model. Lung-image classification (AlexNet, ResNet50, CNN) is planned as the first demonstration workload, and the current worker serves a text-sentiment model. Any model that can sit behind the worker HTTP contract should be servable.

```
React frontend  →  Spring Boot control plane  →  routing engine  →  Python model workers  →  models
```

**Research question:** does resource- and performance-aware routing improve latency, throughput, utilization and reliability compared with basic (round-robin / least-loaded) routing? This will be answered with measurements, not assumptions.

---

## Current status

Last updated: Phase 1 (first end-to-end inference path). Status: **built, awaiting real-environment verification**. See the Phase 1 section below.

| Component | Status | Evidence / notes |
|---|---|---|
| Spring Boot backend | **IMPLEMENTED (Phase 1)** | `GET /api/health` returns plain text `Backend is healthy`. `POST /api/inference` is the unified inference API, forwarding to one worker. CORS allows only the React dev origin. Has 10 tests that use a fake worker. **Not yet compiled or run with real Spring**, because Maven Central is blocked in Claude's sandbox. |
| React frontend | **IMPLEMENTED (Phase 1)** | The workspace sends text requests to `/api/inference` and shows the real label, confidence, model, worker and latency, or a clear error. The header shows live backend status. The Models, Workers and Monitoring pages still show "Not connected" because their APIs come in later phases. 6 tests pass, and build and typecheck pass. |
| Python worker `model-a` | **IMPLEMENTED (single model)** | FastAPI + Hugging Face DistilBERT **text sentiment** model. Endpoints: `GET /health` and `POST /predict` with JSON `{"text": ...}`. Returns label, score, model, revision and inference time. Contract tests pass with a stub model. **Real inference has not yet been run**, because Hugging Face is blocked in Claude's sandbox. |
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
curl http://localhost:8080/api/health
# Backend is healthy
```

Run the tests: `./mvnw test`

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
- The worker's `/health` only reports that the process is up. The model loads at startup, and the process exits if loading fails. Deeper health checks (resource usage, readiness under load) belong to the health-monitoring phase.
- The Models, Workers and Monitoring pages call `/api/models`, `/api/workers` and `/api/metrics`, which do not exist yet. They fall back to "Not connected", and the browser console shows 404s for those calls.
