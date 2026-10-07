# Distributed AI Model Serving Platform

An MSCS capstone project: a **general, model-agnostic** platform for deploying, routing, monitoring and scaling multiple AI models across multiple workers.

It is **not** an application for one specific model. Lung-image classification (AlexNet, ResNet50, CNN) is planned as the first demonstration workload, and the current worker serves a text-sentiment model. Any model that can sit behind the worker HTTP contract should be servable.

```
React frontend  →  Spring Boot control plane  →  routing engine  →  Python model workers  →  models
```

**Research question:** does resource- and performance-aware routing improve latency, throughput, utilization and reliability compared with basic (round-robin / least-loaded) routing? This will be answered with measurements, not assumptions.

---

## Current status

Last updated: Phase 0 (repository cleanup).

| Component | Status | Evidence / notes |
|---|---|---|
| Spring Boot backend | **IMPLEMENTED (minimal)** | Only `GET /api/health` → plain text `Backend is healthy`. The student previously verified it locally. The build has not been re-verified in Claude's cloud sandbox, because Maven Central is blocked there. |
| React frontend | **IMPLEMENTED (UI only)** | `npm ci`, `npm test`, `tsc`, `npm run build` and `npm run dev` all pass. It is **not connected** to the backend and shows "Not connected" instead of fake data. |
| Python worker `model-a` | **IMPLEMENTED (single model)** | FastAPI + Hugging Face DistilBERT **text sentiment** model. Endpoints: `GET /health`, `POST /predict?text=...`. Real inference requires downloading the model from Hugging Face on first start. |
| AlexNet / ResNet50 / CNN | **NOT IN REPO** | No code, weights, dataset or evaluation scripts committed yet. No accuracy numbers exist. |
| Unified inference API (`/api/inference`) | PLANNED — Phase 1 | |
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
curl -X POST "http://localhost:8001/predict?text=I%20love%20this"
```

### 3. Frontend: http://localhost:5173

```bash
cd frontend
npm ci
npm run dev
```

Other commands:
- `npm test` — unit tests
- `npm run lint` — lint
- `npm run build` — production build

The UI reads the backend address from `VITE_API_BASE_URL`. When that variable is unset, every page shows "Not connected".

## Development workflow

- `main` only receives reviewed work.
- Each phase is built on its own branch, named `phase-<N>-<topic>`.
- Each branch is independently tested before merging.
- Technical decisions are recorded in [DECISIONS.md](DECISIONS.md).
- Never commit secrets, `.env` files, `node_modules`, build output, or model weights.

## Known issues

- The frontend build still depends on `@lovable.dev/vite-tanstack-config`, the generator's shared Vite config. It works, but it should be replaced with an explicit Vite config in a later cleanup.
- The frontend's production build targets Cloudflare Workers by default (via nitro). This is irrelevant for local development.
- The worker's `/health` returns "healthy" even if the model failed to load.
