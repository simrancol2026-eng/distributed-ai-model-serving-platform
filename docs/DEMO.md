# Sprint 2 native-stack demo

For what to say and show, use the [complete presentation script, STAR explanation and simple example](SPRINT2_PRESENTATION.md).

This is a rehearsal runbook, not a record of a completed demo. Code baseline: merge `1c1d932eba0dd75a8920186d4571cce0eeaba470`, whose tree matches tested `2f731ba2991cbcfbb68191e0c620f3b89da1b238`. Record the actual checkout SHA, time, environment and outputs for each rehearsal.

## Prepare

Use Java 21, Python 3.12, Node 22/npm 10 and a reachable native PostgreSQL database. Docker is **WORK IN PROGRESS**: container build/run validation and the full multi-service setup are incomplete; the demo uses native processes. Follow [the README setup](../README.md#running-locally) to create a dedicated demo database/role and install dependencies. Keep credentials in terminal environment variables, never in recordings or committed files. Flyway applies V1–V3 at backend startup.

Run four terminals: worker A, worker B, backend, frontend. Download the model before the presentation. Two processes running the same revision demonstrate worker distribution; they do not demonstrate two distinct model versions.

PowerShell, worker A (from repository root):

```powershell
cd model-workers/model-a
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
$env:WORKER_ID = 'worker-a'
$env:MODEL_REVISION = '714eb0fa89d2f80546fda750413ed43d93601a13'
.\.venv\Scripts\python.exe -m uvicorn worker:app --port 8001
```

Worker B in another terminal, reusing the installed environment:

```powershell
cd model-workers/model-a
$env:WORKER_ID = 'worker-b'
$env:MODEL_REVISION = '714eb0fa89d2f80546fda750413ed43d93601a13'
.\.venv\Scripts\python.exe -m uvicorn worker:app --port 8002
```

Backend terminal: set PLATFORM_DB_URL, PLATFORM_DB_USER and PLATFORM_DB_PASSWORD to the existing demo database credentials before running:

```powershell
cd backend/spring-boot
$env:PLATFORM_WORKER_URLS = 'http://localhost:8001,http://localhost:8002'
.\mvnw.cmd spring-boot:run
```

Frontend terminal:

```powershell
cd frontend
npm ci
$env:VITE_API_BASE_URL = 'http://localhost:8080'
npm run dev -- --port 5173 --strictPort
```

Open http://localhost:5173. The explicit port matches the backend’s default CORS origins; `--strictPort` stops startup if 5173 is occupied instead of silently selecting another port. The backend health endpoint is process liveness, not worker readiness. Check each worker separately:

```powershell
Invoke-RestMethod http://localhost:8080/api/health
Invoke-RestMethod http://localhost:8001/health
Invoke-RestMethod http://localhost:8002/health
```

Require distinct worker IDs/PIDs and the expected full revision before continuing. From a fifth terminal at the repository root, seed the live model:

```powershell
python scripts/seed_registry.py --base-url http://localhost:8080
```

The seed reads a real inference response. It is idempotent for matching metadata and fails without overwriting conflicting v1 metadata. Investigate a conflict; do not reset shared data.

## Bash equivalents (Linux/macOS)

Run each service in its own terminal from the repository root. Set the existing demo database environment variables privately before starting Spring. Python 3.12 and the other prerequisites above still apply.

Worker A:
```bash
cd model-workers/model-a
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
WORKER_ID=worker-a MODEL_REVISION=714eb0fa89d2f80546fda750413ed43d93601a13 .venv/bin/python -m uvicorn worker:app --port 8001
```

Worker B (after A's dependency installation finishes):
```bash
cd model-workers/model-a
WORKER_ID=worker-b MODEL_REVISION=714eb0fa89d2f80546fda750413ed43d93601a13 .venv/bin/python -m uvicorn worker:app --port 8002
```

Backend:
```bash
cd backend/spring-boot
PLATFORM_WORKER_URLS=http://localhost:8001,http://localhost:8002 ./mvnw spring-boot:run
```

Frontend:
```bash
cd frontend
npm ci
VITE_API_BASE_URL=http://localhost:8080 npm run dev -- --port 5173 --strictPort
```

Checks and seed from repository root:
```bash
curl -fsS http://localhost:8080/api/health
curl -fsS http://localhost:8001/health
curl -fsS http://localhost:8002/health
python3 scripts/seed_registry.py --base-url http://localhost:8080
curl -fsS http://localhost:8080/api/models/sentiment-distilbert/versions
body='{"capability":"TEXT","input":"This movie is wonderful.","model":"sentiment-distilbert","version":"v1"}'
response=$(curl -fsS -H 'Content-Type: application/json' -d "$body" http://localhost:8080/api/inference)
printf '%s\n' "$response"
request_id=$(printf '%s' "$response" | python3 -c 'import json,sys; print(json.load(sys.stdin)["requestId"])')
curl -fsS "http://localhost:8080/api/requests/$request_id"
for i in 1 2 3 4 5 6; do
  curl -fsS -H 'Content-Type: application/json' -d "$body" http://localhost:8080/api/inference
  printf '\n'
done
```

For the optional automated checks below, use python3 instead of python in bash. Grok reports a successful native bash rehearsal at c818172; the literal PowerShell blocks were not executed because that test host lacked pwsh. These newly added bash blocks still require review against that rehearsal.

## Present (approximately 8–10 minutes)

1. **Architecture and limits.** React sends requests to Spring; Spring resolves registered versions, selects workers and stores best-effort lifecycle/decision data in PostgreSQL. The current workload is text sentiment. Intelligent routing, failover, Kafka, Redis and scaling are future work.
2. **Registry.** Open Models and show the seeded model. Use GET /api/models/sentiment-distilbert/versions to show the immutable artifact revision. Explain that registration does not deploy a model.
3. **Real inference.** Submit a positive or negative sentence in the workspace. Read the actual label, confidence and worker shown in the UI. The workspace does not display the request ID; obtain it from the POST response in browser developer tools (Network), or use the terminal request below and trace that separate request. Do not promise a specific measured latency.
4. **Traceability.** Fetch the request by ID. Compare the response worker/decision with the persisted record and the backend's single routing decision log line.
5. **Two workers.** Run the sequence below while other clients are idle. Show alternating endpoints and matching chosen endpoints. The starting worker depends on previous requests; do not require A to start.
6. **Version selection and rejection.** Send a request explicitly naming the seeded v1. If demonstrating an unserved revision, use the existing version-dispatch test below against the dedicated demo database; it creates a synthetic version and proves rejection. Clearly state that the synthetic revision was never loaded or executed.
7. **Close with evidence and gaps.** Show the tested commit and the evidence index. Tracking is best effort; eligible means matching identity, not live health. Docker remains unverified. Sprint 3 is authorized and tracked separately; this demo does not imply sprint closeout.

PowerShell, real versioned inference and persisted trace:

```powershell
$body = @{
  capability = 'TEXT'
  input = 'This movie is wonderful.'
  model = 'sentiment-distilbert'
  version = 'v1'
} | ConvertTo-Json
$result = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/inference -ContentType application/json -Body $body
$result | ConvertTo-Json -Depth 10
Invoke-RestMethod ("http://localhost:8080/api/requests/" + $result.requestId) | ConvertTo-Json -Depth 10
1..6 | ForEach-Object {
  $r = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/inference -ContentType application/json -Body $body
  [pscustomobject]@{ requestId=$r.requestId; worker=$r.worker; endpoint=$r.workerEndpoint; chosen=$r.routing.chosen }
}
```

Optional automated rehearsal checks from repository root:

```powershell
python scripts/round_robin_e2e_test.py --requests 8 --backend http://localhost:8080 --workers http://localhost:8001,http://localhost:8002
python scripts/version_dispatch_e2e_test.py --requests 4 --backend http://localhost:8080 --workers http://localhost:8001,http://localhost:8002
python scripts/routing_decision_e2e_test.py --backend http://localhost:8080 --model sentiment-distilbert --version v1
```

The version-dispatch test leaves its uniquely named test model in the registry. This is expected; use a dedicated demo database and record the name. Add --log with a captured backend log path to the routing-decision test to check matching log lines.

## Capture and reset

Capture actual frontend inference and actual backend/API or database screens from the same rehearsal. Include request IDs and the commit in an accompanying manifest. Exclude credentials and private input. A screenshot of a stubbed response must be labeled as such. Never substitute an illustrated screen for execution evidence.

Stop only the processes started for this rehearsal with Ctrl+C. Preserve the database and artifacts. Restart using the same commands and rerun health/seed checks; do not drop tables to reset the demo. Keep disruptive worker/database outage tests outside the normal presentation, using the existing reviewed evidence unless a separate rehearsal is planned.

## Rehearsal acceptance

Record pass/fail and evidence location for: startup; both worker identities; seed; registry display; real UI inference; matching persisted request/decision; two-worker alternation; versioned success; unserved-version rejection; and restart. This runbook's commands have been checked against repository interfaces, but this new runbook has not yet been independently rehearsed. Grok owns that review and must report the actual tested SHA and results.
