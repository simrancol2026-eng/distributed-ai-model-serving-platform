# Screenshot evidence — main `39e59e7a2e42ad10f13d5e3e5867d7a96377d4b0`

Screenshots of one Workspace inference per policy, plus the terminal transcript of a different single request under that same Spring process. No product code was changed. Docker was not used. Run details, redacted environment, worker PIDs, model revision, and every request id are in `RUN_RECORD.md`.

This is not a retest, not a repeated evaluation, and it makes no speedup or zero-failure claim.

## What the Workspace page shows

The evidenced page is Workspace (`/`) after one text send. The routing block on that page shows model, worker id, eligible count of pool size, the reason string, latency, and status. The right-hand panel shows eligible workers and the selected worker id.

Policy, `inFlight`, and `health` are not rendered. They appear only in the terminal shots (POST body, `GET /api/requests/{id}`, and the Spring `routing decision` line). A reason string that happens to contain the words "round-robin", "least-in-flight", or "inFlight" is the reason text, not a policy field, an inFlight field, or a health field.

Worker Fleet, Monitoring, Failure recovery, and the compute/memory figures on those screens are not part of this implemented-feature evidence. Screenshots of those pages are not in this tree.

The Workspace shot and the terminal shot for a policy are two requests. Ids are the stored rows.

## ROUND_ROBIN

| File | Request | What is on the image |
| --- | --- | --- |
| `screenshots/rr-workspace-inference.png` | UI `151e9d6a-3226-4817-8378-fafd39e6d60f` | Prompt "RR-UI the concert was absolutely wonderful". Answer POSITIVE. Routing details: model distilbert @ `714eb0fa`, worker `worker-a`, eligible `2 of 2`, reason "no model requested: round-robin over all 2 worker(s)", latency 229 ms, status completed. Right panel: eligible workers `2 of 2`, selected worker `worker-a`. |
| `screenshots/rr-terminal-post.png` | API `95594b07-af34-40f9-ac4d-6a7a9bd02c8e` | Terminal. `git rev-parse HEAD` `39e59e7a2e42ad10f13d5e3e5867d7a96377d4b0`, utc `2026-10-15T23:00:00Z`. POST `/api/inference` HTTP 200 for worker `worker-b`. Backend routing fields are in this transcript, not on the Workspace page. |
| `screenshots/rr-terminal-get-log.png` | API `95594b07-af34-40f9-ac4d-6a7a9bd02c8e` | Terminal. Matching GET and the Spring `routing decision` line for that id. |

## LEAST_IN_FLIGHT

| File | Request | What is on the image |
| --- | --- | --- |
| `screenshots/lif-workspace-inference.png` | UI `0d57a1d3-ce5f-4916-9147-0ea73bfe3124` | Prompt "LIF-UI the concert was absolutely wonderful". POSITIVE. Worker `worker-a`, eligible `2 of 2`, reason "no model requested: least-in-flight over all 2 worker(s)", latency 301 ms, completed. Right panel: eligible `2 of 2`, selected `worker-a`. |
| `screenshots/lif-terminal-post.png` | API `aa082f0a-9fa3-4ec6-a43d-96f1f955f2aa` | Terminal. HEAD as above, utc `2026-10-15T23:12:06Z`. POST HTTP 200 for worker `worker-b`. Backend routing fields are in this transcript. |
| `screenshots/lif-terminal-get-log.png` | API `aa082f0a-9fa3-4ec6-a43d-96f1f955f2aa` | Terminal. Matching GET and the Spring line for that id. |

## HEALTH_LATENCY

| File | Request | What is on the image |
| --- | --- | --- |
| `screenshots/hl-workspace-inference.png` | UI `574cd6b1-bae6-4f2c-bc4b-2dc0a37390af` | Prompt "HL-UI the concert was absolutely wonderful". POSITIVE. Worker `worker-b`, eligible `2 of 2`, reason "minimum (inFlight + 1) * estimated successful predict latency", latency 269 ms, completed. Right panel: eligible workers `2 of 2`, selected worker `worker-b`. |
| `screenshots/hl-terminal-identity-post.png` | API `0bfef5d5-5786-427d-bca8-f846ddf45265` | Terminal. HEAD as above, utc `2026-10-15T23:23:47Z`. Start of POST HTTP 200, worker `worker-a`, endpoint `127.0.0.1:8011`. |
| `screenshots/hl-terminal-routing.png` | API `0bfef5d5-5786-427d-bca8-f846ddf45265` | Terminal. POST routing object for that id, including backend `policy`, `inFlight`, and `health`. |
| `screenshots/hl-terminal-get-log.png` | API `0bfef5d5-5786-427d-bca8-f846ddf45265` | Terminal. Matching GET and the Spring line for that id. |

## Latency figures on the Workspace shots

The page shows 229 ms, 301 ms, and 269 ms. The stored `totalMs` on those same ids is 230, 302, and 272. Worker, eligible count, and reason match the stored row.

## Earlier Sprint 3 screenshots

- `evidence-b75e0ad` at `2691bad7c4fbad0e996ac13ffcd7c482e30139fd` has `evidence/sprint3-b75e0ad/screenshots/desktop-api.png`, `ui-home.png`, `ui-inference.png`, `ui-inference.txt`, and `ui-models.png`, from SHA `b75e0ade91aacdcfb15312e2850e82c86bdf4a8d`.
- `evidence-8a27cea` at `d33b1e98ba62af803212c88f2d9260887029ed58` has no image files and no screenshot paths.
- `evidence-ed28851` at `9f36761e0534a1fcfcc339f610cf41220ce32fce` has no image files and no screenshot paths.
