# Two-machine native inference demo (Windows PowerShell)

This is a **proposed test procedure**, not evidence of a completed multi-machine run. Use two computers on a trusted private network. Do not expose the unauthenticated worker API publicly.

## Topology

- Laptop A (backend): Spring Boot :8080, PostgreSQL :5432, React :5173, optionally Worker A :8001.
- Laptop B (remote worker): Python FastAPI Worker B :8001, running the **same exact model artifact/revision**.
- Both computers on the same reachable LAN; no Docker, Kubernetes or GPU required.

## Laptop B: remote worker

From `model-workers/model-a` in PowerShell:

```powershell
py -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt
$env:WORKER_ID="worker-b"
python -m uvicorn worker:app --host 0.0.0.0 --port 8001
```

Find Laptop B's IPv4 address using `ipconfig`. Allow inbound TCP port 8001 **only from Laptop A / the trusted private network** using the operating system firewall. Some campus/guest Wi-Fi networks block peer-to-peer connections; use a permitted private LAN or VPN instead. Verify the worker locally: `Invoke-RestMethod http://localhost:8001/health`.

## Laptop A: optional local worker

From `model-workers/model-a`:

```powershell
.\.venv\Scripts\Activate.ps1
$env:WORKER_ID="worker-a"
python -m uvicorn worker:app --host 127.0.0.1 --port 8001
```

## Laptop A: check remote reachability before backend startup

Replace `192.168.1.20` with Laptop B's actual private IPv4 address:

```powershell
$remoteWorker="http://192.168.1.20:8001"
Test-NetConnection 192.168.1.20 -Port 8001
Invoke-RestMethod "$remoteWorker/health"
Invoke-RestMethod "$remoteWorker/predict" -Method Post -ContentType "application/json" -Body '{"text":"I love this project"}'
```

Confirm that both workers report the same model revision/artifact. If the remote connection fails, check firewall rules, IP address, Wi-Fi client isolation and worker bind host before debugging routing.

## Laptop A: start backend

Start PostgreSQL using the existing project setup, then from `backend/spring-boot`:

```powershell
$env:PLATFORM_DB_PASSWORD="<your-local-db-password>"
$env:PLATFORM_WORKER_URLS="http://127.0.0.1:8001,http://192.168.1.20:8001"
$env:PLATFORM_ROUTING_POLICY="HEALTH_LATENCY"
$env:WORKER_FAILOVER_ENABLED="true"
$env:WORKER_FAILOVER_BUDGET_MS="10000"
.\mvnw.cmd spring-boot:run
```

The example uses two endpoints on **different physical computers**. Do not commit real passwords or private IP addresses. Run the existing model-registry seed script if your database is new. For the React UI, follow `docs/DEMO.md`.

**Important:** with Sprint 4 failover enabled, worker selection needs fresh background health observations. Immediately after backend startup, requests may return `NO_FRESH_OBSERVATION` until observations are available. Wait for the workers to be observed and retry. Failover is limited to eligible pre-send connection/DNS errors or HTTP 502/503, with at most two distinct endpoints and a shared dispatch budget. **Do not claim a timeout or an already executed request is retried.**

## Evidence checklist (execute and record, do not invent results)

1. On Laptop B, record its private IP, worker ID, process ID and successful `/health` response. Do not publish sensitive network details.
2. On Laptop A, record a successful remote `/predict` call and backend startup showing both endpoints.
3. Submit inference requests through `POST /api/inference` or React. Record returned worker identity, endpoint, policy and request ID.
4. Inspect `GET /api/requests/{id}` and the persisted routing decision. Verify that at least one request was served by **Laptop B**.
5. To demonstrate health-aware avoidance, stop Laptop B's worker; allow health probes to detect the outage; send a new request and verify Laptop A's worker serves it.
6. To demonstrate **actual retry**, use a controlled eligible 502/503 response or pre-send connection failure on a selected worker, with both workers observed healthy beforehand. Capture the two attempt entries and terminal outcome. Merely killing a worker before selection tests health-aware avoidance, **not** bounded failover.
7. Restart Laptop B and verify it returns to eligible healthy status after probes.
8. Preserve exact commands, redacted logs, timestamps, source SHA, request IDs, and results for Grok's independent review.

## Known limits

No shared scheduling counters across backend replicas, no exactly-once guarantee, no full Docker stack, and no verified multi-machine test result until this procedure is run. A second physical worker proves networked inference, not distributed backend orchestration or autoscaling.
