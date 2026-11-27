# December 3 submission: deployment and two-machine evidence

Prepared against `main` `6698fe9eb7d679833ffe4071b4859507ff7d8611`.
This is a runbook, not evidence that a deployment or two-machine test passed.
Record the final tested SHA when running it. The lung-sound experiment in PR #16
is separate from the working X-ray demo and is not a deployed capability.

## What needs Dhir tomorrow

1. A second laptop on the same permitted private LAN (or private VPN), with its
   private IPv4 address and permission to allow a worker port from laptop A only.
2. Both laptops plugged in, awake, with time for the first model download.
3. Existing PostgreSQL credentials entered locally, not pasted into Slack/chat.
4. Hosting account/project access and the intended audience: private assessor
   access is the initial target. A domain/HTTPS origin and spending limit must
   be known before creating paid resources or publishing an internet endpoint.
5. The original audio dataset source URL, to verify patient grouping and terms.
   This does not block the core two-machine TEXT demonstration.

## Deployment decisions and gates

The backend has no application authentication in this baseline. CORS is a browser
policy, not authentication. A production frontend build does not secure the API.

Use one HTTPS origin behind an authenticated gateway/reverse proxy. Route
`/api/*` to Spring Boot and other paths to the tested frontend runtime. Protect
both the dashboard and all API routes; use individual assessor accounts through
the chosen hosting provider's access gateway. The Spring backend must not have
a public bypass around that gateway. Choose the actual gateway/provider with
Dhir before configuring its credentials or domain.

| Component | Exposure and configuration |
| --- | --- |
| Gateway | Public HTTPS only, authenticated access; certificate renewal and access denial tested |
| Frontend | Private upstream behind gateway; build with `VITE_API_BASE_URL=https://<actual-origin>` (no trailing `/api`) |
| Spring Boot | Private upstream on 8080; if proxy is on the same host, bind with `SERVER_ADDRESS=127.0.0.1`; otherwise bind to its private service interface and firewall to gateway only |
| Workers | Private ports only; allow connections from the backend host, not the public internet |
| PostgreSQL | Private 5432; allow backend only; dedicated database role, no public superuser connection |
| Secrets | Deployment secret store/environment; never `VITE_*`, repository, screenshots, or saved command history |

Set `PLATFORM_CORS_ORIGINS` to the exact HTTPS frontend origin if cross-origin
access is used. The current browser client does not opt into cross-origin cookie
credentials, so a separate cookie-authenticated API origin needs additional
implementation and tests; same-origin gateway routing avoids that mismatch.
Use hostnames reachable by the deployed backend for worker URLs. A deployed
browser cannot reach the user's `localhost:8080`.

Configure request-body and rate limits at the gateway for the inference route.
The gateway body limit must accommodate the app's 5 MB image upload after base64
encoding and JSON overhead; test at the accepted boundary and above it. Use a
request timeout consistent with the backend dispatch budget and the browser's
60-second inference timeout. Do not raise the budget above the browser timeout
and claim the entire request is guaranteed to finish within that budget: database
tracking and response work can take additional time.

Before declaring deployment complete, verify unauthenticated dashboard/API
requests are denied; direct backend/worker/database ports are inaccessible from
outside; authenticated inference works; invalid/oversized images fail clearly;
the previous tested release can be restored; and no secrets appear in logs or
browser assets. Record actual outcomes rather than marking this list complete
from configuration alone. The Node production launcher in this change addresses
the earlier `vite preview` missing-server-entry error; run `npm run build` and
`npm run test:production` in `frontend-operations`. A successful build alone is
insufficient, and local HTTP checks do not prove a secure hosted deployment.

## Two-machine demo setup

Use two distinct computers. Laptop A runs Spring Boot, PostgreSQL, the dashboard,
and text worker A. Laptop B runs text worker B. See also [MULTI_MACHINE_DEMO.md](MULTI_MACHINE_DEMO.md).
Use the same source SHA and the same TEXT model artifact/revision on both workers.
No registry seed is required for the unnamed TEXT requests below.

On laptop B, from the extracted repository root in PowerShell (Python 3.12):

```powershell
py -3.12 -m venv "$env:USERPROFILE\venvs\nexus"
& "$env:USERPROFILE\venvs\nexus\Scripts\python.exe" -m pip install -r .\model-workers\model-a\requirements.txt
$env:WORKER_TASK='text'
$env:WORKER_ID='worker-b'
& "$env:USERPROFILE\venvs\nexus\Scripts\python.exe" -m uvicorn worker:app --app-dir model-workers/model-a --host 0.0.0.0 --port 8001
```

Keep that terminal running. The short environment path avoids the earlier Windows
long-path installation failure. Verify locally at `http://127.0.0.1:8001/health`.
Use `ipconfig` to identify B's private IPv4 address. Scope the OS firewall rule
for TCP 8001 to A's IP on the private network; do not disable the firewall.

Keep A's existing text worker on `127.0.0.1:8001` running. From A, replace the
example address below with B's actual private address:

```powershell
$remoteHost='192.168.1.20'
$remoteWorker="http://${remoteHost}:8001"
Test-NetConnection $remoteHost -Port 8001
Invoke-RestMethod "$remoteWorker/health"
Invoke-RestMethod "$remoteWorker/predict" -Method Post -ContentType 'application/json' -Body '{"text":"I enjoyed this project."}'
```

Confirm actual model/revision equality from both `/health` responses. A refused
connection, guest-network isolation or timeout must be resolved before testing
routing. Do not call a localhost pair a two-machine demonstration.

Restart A's backend in its existing configured database terminal, from
`backend/spring-boot`, with these settings. Preserve its working DB and specialist
worker environment variables; do not replace credentials with placeholders.

```powershell
$env:PLATFORM_WORKER_URLS="http://127.0.0.1:8001,http://${remoteHost}:8001"
$env:PLATFORM_ROUTING_POLICY='ROUND_ROBIN'
$env:WORKER_FAILOVER_ENABLED='true'
$env:WORKER_FAILOVER_BUDGET_MS='10000'
.\mvnw.cmd spring-boot:run
```

`$remoteHost` must be defined in this backend terminal too. Existing specialist
settings are `PLATFORM_CODE_WORKER_URLS`, `PLATFORM_IMAGE_WORKER_URLS` and
`PLATFORM_HEALTHCARE_WORKER_URLS`. Only TEXT needs two workers for this demo.
Wait for model startup and background health observations before inference.

## Capture real behavior

From a separate PowerShell terminal on A:

```powershell
$api='http://127.0.0.1:8080'
Invoke-RestMethod "$api/api/health"
Invoke-RestMethod "$api/api/capabilities" | ConvertTo-Json -Depth 10
$payload=@{capability='TEXT'; input='I enjoyed this project.'} | ConvertTo-Json
1..4 | ForEach-Object {
    $result=Invoke-RestMethod "$api/api/inference" -Method Post -ContentType 'application/json' -Body $payload
    $result | Select-Object requestId,worker,workerEndpoint,model,version,totalLatencyMs
    $result.routing | ConvertTo-Json -Depth 10
    Invoke-RestMethod "$api/api/requests/$($result.requestId)" | ConvertTo-Json -Depth 10
}
```

`GET /api/health` is plain-text process liveness only. `/api/capabilities` checks
workers and reports configured/healthy counts, identities, routing policy and
failover settings. A zero `inFlight` snapshot between requests is normal; it is
not CPU utilization. At least one recorded inference must name B's endpoint.

Test health-aware avoidance by stopping B's worker, waiting for detection and
submitting another request. Capture B as unreachable and A serving the request.
Restart B and capture healthy recovery. This proves outage detection and
continued service, not necessarily a retry within the same request.

Actual failover needs one response with two entries in `routing.attempts`:
first `FAILED`, then `SUCCEEDED`, with different endpoints on distinct machines.
Use a controlled eligible pre-send connection failure or test-only 502/503 fault
after both workers have fresh healthy observations. Label any fault-injection
worker explicitly; it is not real model output. There is no production fault
toggle in the baseline. Simply stopping a worker before routing may cause it to
be skipped and will not guarantee two attempts. Do not change healthy selection
logic just to manufacture the demonstration. Timeouts are not retried.

For the captured request, compare response `routing.attempts`, final
`workerEndpoint`, `requestId`, and the persisted record from
`GET /api/requests/{requestId}`. Tracking must be available; a successful model
response without a persisted row is not proof that persistence passed.

## Submission evidence and presentation

Keep one evidence folder with exact source SHA, runtime/model revisions, commands,
timestamps, health/capability responses, real inference responses, the same
request's persisted routing record, and redacted screenshots of both machines.
Do not publish credentials, patient images/audio, or identifying metadata.

Show in this order: healthy workers on two hosts; normal request distribution;
remote inference; detected failure and continued service; actual retry evidence
(if separately captured); recovery; then the healthcare research warning.
Distinguish implemented behavior from planned autoscaling and exploratory audio.

The final report should list each requirement with its evidence and limitation,
not an unsupported completion percentage. X-ray scores are research outputs,
not medical diagnosis or verified disease probabilities. The audio baseline's
development figures are not final heldout accuracy. Deployment, two-machine
execution and final report/presentation readiness remain pending until their
respective artifacts and observed results exist.
