# Run record — screenshot job on main `39e59e7a2e42ad10f13d5e3e5867d7a96377d4b0`

One checkout. Spring was restarted between policies. Workers and Vite were not restarted between policies. This file does not report a retest, a repeated evaluation, a speedup, or a failure count.

`git status --porcelain` was not executed while each Spring process was running. The command was run later, on this evidence branch, after the screenshot commit and after Spring, Vite, and both workers had been stopped. Output:

```
(no lines)
```

That empty output is the same fact for all three policy runs: the only commit after `39e59e7a2e42ad10f13d5e3e5867d7a96377d4b0` adds files under `evidence/sprint3-main-39e59e7/`, and the working tree has no further porcelain lines. During the runs the checkout was detached at that SHA. Files created in the tree for the run are gitignored, so they do not appear in porcelain:

```
frontend/.gitignore:17:*.local    frontend/.env.local
frontend/.gitignore:10:node_modules    frontend/node_modules
backend/spring-boot/.gitignore:2:target/    backend/spring-boot/target
```

`frontend/node_modules` was a symlink to `/tmp/s3-8a27cea/frontend/node_modules` (same `package-lock.json` hash). `frontend/.env.local` contained only `VITE_API_BASE_URL=http://localhost:8080`.

## Shared config

Database `s3_39e59e7` on PostgreSQL 16 at `127.0.0.1:5432`, created empty (`public_tables` 0 before Flyway). Role `s3_39e59e7`. Password read from a file at process start and not written here.

Workers, both still the same processes for every policy:

| Worker | PID | Command |
| --- | --- | --- |
| worker-a | 177570 | `/tmp/worker-venv/bin/python3 /tmp/worker-venv/bin/uvicorn worker:app --host 127.0.0.1 --port 8011` |
| worker-b | 177583 | `/tmp/worker-venv/bin/python3 /tmp/worker-venv/bin/uvicorn worker:app --host 127.0.0.1 --port 8012` |

Working directory for both: `/tmp/s3-39e59e7/model-workers/model-a`. Environment: `WORKER_ID` as above, `MODEL_REVISION=714eb0fa89d2f80546fda750413ed43d93601a13`. Model name is the worker default `distilbert-base-uncased-finetuned-sst-2-english`. PIDs were read from `/proc` while those processes were alive. At 22:54:21 UTC both `GET /health` calls returned HTTP 200. The body prefix for worker-a was `{"status":"healthy","worker":"worker-a","model":"distilbert-base-uncased-finetuned-sst-2-english","revision":"714eb0fa89d2f80546fda750413ed43d93601a13","capability":"TEXT","task":` and worker-b was the same with `"worker":"worker-b"`. The read was truncated after `task`, so the `pid` field in `/health` was not captured. The same revision is the `version` field on the inference responses.

Vite PID 177608: `node /tmp/s3-39e59e7/frontend/node_modules/.bin/vite dev --host 127.0.0.1 --port 5173 --strictPort`, cwd `/tmp/s3-39e59e7/frontend`. It answered HTTP 200 on `http://127.0.0.1:5173/`.

Spring, each time: cwd `/tmp/s3-39e59e7/backend/spring-boot`, command `./mvnw -B spring-boot:run`.

Environment passed to every Spring start:

```
PLATFORM_DB_URL=jdbc:postgresql://localhost:5432/s3_39e59e7
PLATFORM_DB_USER=s3_39e59e7
PLATFORM_DB_PASSWORD value redacted (set from a file; the value is not in this record)
PLATFORM_WORKER_URLS=http://127.0.0.1:8011,http://127.0.0.1:8012
PLATFORM_WORKER_URL unset
PLATFORM_BACKEND_PORT unset
PLATFORM_ROUTING_POLICY=<the policy for that start>
```

`server.port` stayed the default 8080. No other `PLATFORM_*` variables were set by the launcher.

## ROUND_ROBIN

`git status --porcelain`: the empty result above. It was not snapshotted at 22:54Z.

`PLATFORM_ROUTING_POLICY=ROUND_ROBIN`. Java PID 178843. Log line: `worker pool: 2 worker(s), policy=ROUND_ROBIN order [127.0.0.1:8011, 127.0.0.1:8012]` at `2026-10-15T22:54:12.257Z`. Tomcat on 8080 at `2026-10-15T22:54:12.914Z`.

Worker PIDs 177570 and 177583, revision `714eb0fa89d2f80546fda750413ed43d93601a13`.

Request ids in this Spring log, in order:

| Request id | Role | Chosen |
| --- | --- | --- |
| `3f8a89f0-c63a-4752-9b69-22ffd6d94e00` | smoke POST before the screenshots | `127.0.0.1:8011` |
| `03385357-92cc-4950-973b-8b30133bc079` | size-check POST before the screenshots | `127.0.0.1:8012` |
| `151e9d6a-3226-4817-8378-fafd39e6d60f` | browser Workspace screenshot | `127.0.0.1:8011` / worker-a |
| `95594b07-af34-40f9-ac4d-6a7a9bd02c8e` | `show_policy.py ROUND_ROBIN` terminal shots | `127.0.0.1:8012` / worker-b |

## LEAST_IN_FLIGHT

`git status --porcelain`: the same empty result. Not snapshotted at 23:08Z.

`PLATFORM_ROUTING_POLICY=LEAST_IN_FLIGHT`. Java PID 182461. Log line: `worker pool: 2 worker(s), policy=LEAST_IN_FLIGHT order [127.0.0.1:8011, 127.0.0.1:8012]` at `2026-10-15T23:08:07.928Z`. Tomcat on 8080 at `2026-10-15T23:08:08.469Z`.

Same worker PIDs and revision.

Request ids in this Spring log:

| Request id | Role | Chosen |
| --- | --- | --- |
| `0d57a1d3-ce5f-4916-9147-0ea73bfe3124` | browser Workspace screenshot | `127.0.0.1:8011` / worker-a |
| `aa082f0a-9fa3-4ec6-a43d-96f1f955f2aa` | `show_policy.py LEAST_IN_FLIGHT` terminal shots | `127.0.0.1:8012` / worker-b |

## HEALTH_LATENCY

`git status --porcelain`: the same empty result. Not snapshotted at 23:17Z.

`PLATFORM_ROUTING_POLICY=HEALTH_LATENCY`. Java PID 184244. Log line: `worker pool: 2 worker(s), policy=HEALTH_LATENCY order [127.0.0.1:8011, 127.0.0.1:8012]` at `2026-10-15T23:17:24.996Z`. Tomcat on 8080 at `2026-10-15T23:17:25.605Z`.

Same worker PIDs and revision.

Request ids in this Spring log:

| Request id | Role | Chosen |
| --- | --- | --- |
| `9d593d2c-aca1-4eae-a18c-a3cef7fc10af` | one POST after startup; both candidates `health.live` true | `127.0.0.1:8011` |
| `574cd6b1-bae6-4f2c-bc4b-2dc0a37390af` | browser Workspace screenshot | `127.0.0.1:8012` / worker-b |
| `0bfef5d5-5786-427d-bca8-f846ddf45265` | `show_policy.py HEALTH_LATENCY` terminal shots | `127.0.0.1:8011` / worker-a |

Policy, `inFlight`, and `health` are fields on those backend bodies and log lines. They are not fields on the Workspace page.
