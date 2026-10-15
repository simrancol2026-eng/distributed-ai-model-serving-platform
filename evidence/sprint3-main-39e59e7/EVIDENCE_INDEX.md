# Screenshot evidence — main `39e59e7a2e42ad10f13d5e3e5867d7a96377d4b0`

Screenshots only. No product code was changed. Docker was not used.

Stack for these shots: empty PostgreSQL 16 database `s3_39e59e7` (0 public tables before Flyway), two real DistilBERT workers (`worker-a` on `127.0.0.1:8011`, `worker-b` on `127.0.0.1:8012`, revision `714eb0fa89d2f80546fda750413ed43d93601a13`), Spring Boot on port 8080, Vite on `http://127.0.0.1:5173`. Spring was restarted for each policy. `git rev-parse HEAD` in the terminal shots is `39e59e7a2e42ad10f13d5e3e5867d7a96377d4b0`.

## What the UI actually shows

There is no page titled Dashboard. Routes are Workspace (`/`), History, Saved, Models, Workers, Routing, Monitoring, and Failure recovery.

The live routing decision is on the Workspace page after a text inference, plus the right-hand panel:

- Worker id, eligible count of pool size, and the reason string are rendered.
- The frontend does not render a policy field or a strategy field. Policy and strategy are in the terminal JSON, the stored `GET /api/requests/{id}` body, and the Spring `routing decision` log line.

Worker Fleet (`/workers`) and Monitoring (`/monitoring`) are stubbed at this SHA. `GET /api/workers` and `GET /api/metrics` are not implemented; the metric strip is hardcoded "Not connected" / "—", the worker table says "No workers discovered", and the charts say "Waiting for backend". The same stub was on screen under every policy. Nothing here labels that stub as live.

ROUND_ROBIN and LEAST_IN_FLIGHT candidate JSON has `inFlight` and no `health` object. HEALTH_LATENCY candidate JSON includes `health` (`live`, `healthAgeMs`, `ewmaMs`, `samples`, `estimateMs`, `score`, `cooldownMs`).

The Workspace screenshot and the terminal screenshot for a policy are two different requests. Both were served by the Spring process started with that policy. Ids below are the stored rows.

## ROUND_ROBIN

Spring log: `worker pool: 2 worker(s), policy=ROUND_ROBIN`.

| File | Request | What is on the image |
| --- | --- | --- |
| `screenshots/rr-workspace-inference.png` | UI `151e9d6a-3226-4817-8378-fafd39e6d60f` | Prompt "RR-UI the concert was absolutely wonderful". Answer POSITIVE. Routing details: model distilbert @ `714eb0fa`, worker `worker-a`, eligible `2 of 2`, reason "no model requested: round-robin over all 2 worker(s)", latency 229 ms, status completed. Right panel: eligible workers `2 of 2`, selected worker `worker-a`. Policy is not a UI field. Stored row: worker-a, `127.0.0.1:8011`, totalMs 230, policy `ROUND_ROBIN`, strategy `ROUND_ROBIN_ALL`. |
| `screenshots/rr-workers.png` | none (stub) | Title Worker Fleet. Metric strip "Not connected". Table: "No workers discovered / Not connected to the worker fleet". |
| `screenshots/rr-monitoring.png` | none (stub) | Title Monitoring. Platform Health values "Not connected". Charts "Waiting for backend". This is not a Dashboard page. |
| `screenshots/rr-terminal-post.png` | API `95594b07-af34-40f9-ac4d-6a7a9bd02c8e` | `git rev-parse HEAD` `39e59e7a2e42ad10f13d5e3e5867d7a96377d4b0`, utc `2026-10-15T23:00:00Z`, policy arg `ROUND_ROBIN`. POST `/api/inference` HTTP 200. `routing.policy` `ROUND_ROBIN`, `strategy` `ROUND_ROBIN_ALL`, eligible 2, both candidates `inFlight` 0, note `ANY_WORKER`, no health object, chosen `127.0.0.1:8012`, worker `worker-b`. |
| `screenshots/rr-terminal-get-log.png` | API `95594b07-af34-40f9-ac4d-6a7a9bd02c8e` | `GET /api/requests/95594b07-af34-40f9-ac4d-6a7a9bd02c8e` status SUCCEEDED, same routing policy/strategy/candidates. Spring line `routing decision requestId=95594b07-af34-40f9-ac4d-6a7a9bd02c8e` with `policy=ROUND_ROBIN`. |

## LEAST_IN_FLIGHT

Spring log: `worker pool: 2 worker(s), policy=LEAST_IN_FLIGHT`.

| File | Request | What is on the image |
| --- | --- | --- |
| `screenshots/lif-workspace-inference.png` | UI `0d57a1d3-ce5f-4916-9147-0ea73bfe3124` | Prompt "LIF-UI the concert was absolutely wonderful". POSITIVE. Worker `worker-a`, eligible `2 of 2`, reason "no model requested: least-in-flight over all 2 worker(s)", latency 301 ms, completed. Right panel eligible `2 of 2`, selected `worker-a`. Policy is not a UI field. Stored row: worker-a, `127.0.0.1:8011`, totalMs 302, policy `LEAST_IN_FLIGHT`, strategy `ROUND_ROBIN_ALL`. |
| `screenshots/lif-workers.png` | none (stub) | Worker Fleet. Metrics "Not connected". "No workers discovered". |
| `screenshots/lif-monitoring.png` | none (stub) | Monitoring. Metrics "Not connected". Charts "Waiting for backend". |
| `screenshots/lif-terminal-post.png` | API `aa082f0a-9fa3-4ec6-a43d-96f1f955f2aa` | HEAD `39e59e7a2e42ad10f13d5e3e5867d7a96377d4b0`, utc `2026-10-15T23:12:06Z`, policy arg `LEAST_IN_FLIGHT`. POST HTTP 200. `routing.policy` `LEAST_IN_FLIGHT`, `strategy` `ROUND_ROBIN_ALL`, candidates `inFlight` 0, no health object, chosen `127.0.0.1:8012`, worker `worker-b`. |
| `screenshots/lif-terminal-get-log.png` | API `aa082f0a-9fa3-4ec6-a43d-96f1f955f2aa` | Matching GET status SUCCEEDED and the Spring line `routing decision requestId=aa082f0a-9fa3-4ec6-a43d-96f1f955f2aa` with `policy=LEAST_IN_FLIGHT`. |

## HEALTH_LATENCY

Spring log: `worker pool: 2 worker(s), policy=HEALTH_LATENCY`. Both workers were `live=true` before the UI request.

| File | Request | What is on the image |
| --- | --- | --- |
| `screenshots/hl-workspace-inference.png` | UI `574cd6b1-bae6-4f2c-bc4b-2dc0a37390af` | Prompt "HL-UI the concert was absolutely wonderful". POSITIVE. Worker `worker-b`, eligible `2 of 2`, reason "minimum (inFlight + 1) * estimated successful predict latency", latency 269 ms, completed. Right panel: eligible workers `2 of 2`, selected worker `worker-b`. The reason does not print the policy name, and the page has no policy field. Stored row: worker-b, `127.0.0.1:8012`, totalMs 272, policy `HEALTH_LATENCY`, strategy `ROUND_ROBIN_ALL`. |
| `screenshots/hl-workers.png` | none (stub) | Worker Fleet. Metrics "Not connected". "No workers discovered". |
| `screenshots/hl-monitoring.png` | none (stub) | Monitoring. Metrics "Not connected". Charts "Waiting for backend". |
| `screenshots/hl-terminal-identity-post.png` | API `0bfef5d5-5786-427d-bca8-f846ddf45265` | HEAD `39e59e7a2e42ad10f13d5e3e5867d7a96377d4b0`, utc `2026-10-15T23:23:47Z`, policy arg `HEALTH_LATENCY`. Start of POST `/api/inference` HTTP 200, request id `0bfef5d5-5786-427d-bca8-f846ddf45265`, worker `worker-a`, endpoint `127.0.0.1:8011`. |
| `screenshots/hl-terminal-routing.png` | API `0bfef5d5-5786-427d-bca8-f846ddf45265` | POST `routing` object: `strategy` `ROUND_ROBIN_ALL`, `policy` `HEALTH_LATENCY`, eligible 2, both candidates `inFlight` 0, note `ELIGIBLE`, `health.live` true, `healthAgeMs` 1992 and 1991, scores 100.0, chosen `127.0.0.1:8011`. |
| `screenshots/hl-terminal-get-log.png` | API `0bfef5d5-5786-427d-bca8-f846ddf45265` | GET `/api/requests/0bfef5d5-5786-427d-bca8-f846ddf45265` routing with the same policy and health fields, and the Spring line `routing decision requestId=0bfef5d5-5786-427d-bca8-f846ddf45265` with `policy=HEALTH_LATENCY`. |

## Earlier Sprint 3 screenshots

Inspected branch tips:

- `evidence-b75e0ad` at `2691bad7c4fbad0e996ac13ffcd7c482e30139fd` has screenshots under `evidence/sprint3-b75e0ad/screenshots/`: `desktop-api.png`, `ui-home.png`, `ui-inference.png`, `ui-inference.txt`, `ui-models.png`. That index calls them workspace pages and an API JSON desktop shot. They are from SHA `b75e0ade91aacdcfb15312e2850e82c86bdf4a8d`, not from this main SHA, and not HEALTH_LATENCY.
- `evidence-8a27cea` at `d33b1e98ba62af803212c88f2d9260887029ed58` has no png, jpg, jpeg, webp, or gif, and no path named screenshot. Evidence there is logs and the written report.
- `evidence-ed28851` at `9f36761e0534a1fcfcc339f610cf41220ce32fce` has no image files and no screenshot paths. Evidence there is logs, the report, and harness files.

## Latency figures

The Workspace page shows the response's `totalLatencyMs` (229, 301, and 269 on the three inference shots). The stored `totalMs` on those same request ids is 230, 302, and 272. Worker, eligible count, and reason match the stored row.
