FROM: Grok
TO: Claude, Codex-ChatGPT
TYPE: TEST REPORT
PHASE: Sprint 2 docs and native-stack demo rehearsal (review only)
COMMIT: c818172072d4c8397a4689395a05d95dfb7b3d25
BRANCH: codex/sprint2-docs-demo (detached checkout; not modified; not pushed)

Read-only on code branches. No commits and no pushes. `git status` after the rehearsal is `?? artifacts/` only. `origin/main` is `1c1d932eba0dd75a8920186d4571cce0eeaba470`. This commit's parent is `ac290871e449ca8350c915df405a40cdf21bf212`. `git merge-base --is-ancestor 1c1d932eba0dd75a8920186d4571cce0eeaba470 HEAD` succeeded. Message: "docs: record verified Sprint 2 work and remaining closeout gates". Diff against `1c1d932`: `README.md`, `docs/DEMO.md`, `docs/SPRINT2_CLOSEOUT.md` only (3 files, 172 insertions, 3 deletions).

Docker was not used. It stays deferred and unverified. BLOCKED is not a pass.

## Environment

- Ubuntu 24.04.5 LTS, kernel 6.12.94+, x86_64. Display `:1` (1920×1200).
- Java OpenJDK 21.0.12.1. Node v22.14.0, npm 10.9.9. Python 3.12.3.
- PostgreSQL 16.15 (Ubuntu 16.15-0ubuntu0.24.04.1), `psql` 16.15. Dedicated database `platform_demo`, role `platform_demo`, created by `scripts/setup_registry_db.sh` because it did not exist. Password stayed in the process environment. It is not in this report or the copied logs (byte scan of the text artifacts: 0 hits).
- Workers: existing `/tmp/worker-venv` (torch and transformers already installed). No new `model-workers/model-a/.venv` was created: that path is not gitignored, and creating it would have dirtied the tree. `MODEL_REVISION=714eb0fa89d2f80546fda750413ed43d93601a13` was set, as the runbook says.
- `pwsh` is not installed. The PowerShell blocks in `docs/DEMO.md` were not executed.

## Tree and evidence claims

- `1c1d932eba0dd75a8920186d4571cce0eeaba470` is the merge of `87eaf0f` and `2f731ba2991cbcfbb68191e0c620f3b89da1b238`. Its tree and `2f731ba`'s tree are both `31795af55cc7810fe0fa08963354839760ea810a`. `DEMO.md`'s sentence that the merge tree matches tested `2f731ba` is true.
- `31795af55cc7810fe0fa08963354839760ea810a` is that tree object. `git rev-parse 31795af^{commit}` fails. `SPRINT2_CLOSEOUT.md` calls it the "tested head". The identity is true; the label is not. See doc bugs.
- Commits `e70b733..87eaf0f` change only `DECISIONS.md` and `README.md`, plus the merge commit. The closeout line that subsequent lifecycle-branch changes were documentation matches that diff.
- Listed tested and merge SHAs in the closeout table all resolve: `f581a15`, `3ace7ad`, `0a056a9`, `6ebec8f`, `4351f23`, `856caaa`, `e70b733`, `87eaf0f`, `2f731ba`, `1c1d932`. `8bfc496` and `65d404b` resolve. At `8bfc496`, `@Test` counts are ApplicationTests 1, InferenceApiTest 9, WorkerDownTest 1, WorkerUrlValidatorTest 7 (sum 18), which matches the README's historical sentence. Current `WorkerUrlValidatorTest` has 8 tests; the README does not claim that 7 is the current count.
- Evidence commit `3cb00e8a3bbd8fd7edf404f4f878a08b9ba25c3f` contains `evidence/sprint2-5-2f731ba/`. The closeout counts (Maven 86, frontend 12, scripts 38/38, 19/19, 23/23, 17/17, 46/46, phase 1 8/8, open-gate max 4.058 s, slow-worker 32.013 s) match that report. They were not re-run in this review.

## Demo rehearsal

Literal PowerShell was not run. Each later row is the bash equivalent of that step. Deviations are in the command cell.

| step | PASS/FAIL/BLOCKED | command | actual output |
| --- | --- | --- | --- |
| 1 | PASS | `git fetch origin codex/sprint2-docs-demo && git checkout --detach c818172072d4c8397a4689395a05d95dfb7b3d25` | HEAD `c818172072d4c8397a4689395a05d95dfb7b3d25`. Branch tip equals that SHA. |
| 2 | BLOCKED | `docs/DEMO.md` PowerShell blocks (`.\.venv\Scripts\python.exe`, `.\mvnw.cmd`, `Invoke-RestMethod`, `$env:`) | `pwsh` is not on this host (`command -v pwsh` empty). Those commands were not run. Not counted as a pass. |
| 3 | PASS | `sudo -u postgres env PLATFORM_DB_NAME=platform_demo PLATFORM_DB_USER=platform_demo PLATFORM_DB_PASSWORD=… bash scripts/setup_registry_db.sh` | `role platform_demo: creating`. `database platform_demo: creating (owner platform_demo)`. |
| 4 | PASS | Two processes: `WORKER_ID=worker-a` port 8001 and `WORKER_ID=worker-b` port 8002, `MODEL_REVISION=714eb0fa89d2f80546fda750413ed43d93601a13`, `/tmp/worker-venv` uvicorn. | A pid 117100 and B pid 117090. Both `status=healthy`, worker ids `worker-a` and `worker-b`, model `distilbert-base-uncased-finetuned-sst-2-english`, revision `714eb0fa89d2f80546fda750413ed43d93601a13`, `predictionsServed=0`. Distinct pids. |
| 5 | PASS | `cd backend/spring-boot && PLATFORM_WORKER_URLS=http://localhost:8001,http://localhost:8002 PLATFORM_DB_URL=jdbc:postgresql://localhost:5432/platform_demo PLATFORM_DB_USER=platform_demo ./mvnw -B spring-boot:run` | Started in 2.078 s, pid 117301. Log: `worker pool: 2 worker(s), round-robin order [localhost:8001, localhost:8002]`. Flyway on empty schema: V1 registry, V2 inference requests, V3 routing decision. `Successfully applied 3 migrations … now at version v3 (00:00.029s)`. Datasource line: `product=PostgreSQL version=16.15` with no URL and no password. Flyway also logged `Database: jdbc:postgresql://localhost:5432/platform_demo (PostgreSQL 16.15)`. |
| 6 | PASS | `cd frontend && npm ci`; `VITE_API_BASE_URL=http://localhost:8080 npm run dev -- --host 127.0.0.1 --port 5173 --strictPort` | npm ci: added 536, audited 537, 0 vulnerabilities, exit 0. Vite HTTP 200 on `http://127.0.0.1:5173/` and on `http://localhost:5173/`. |
| 7 | PASS | `curl -D - http://127.0.0.1:8080/api/health` and both worker `/health` URLs | Backend HTTP 200, `Content-Type: text/plain`, body `Backend is healthy`. Worker bodies as in row 4. |
| 8 | PASS | `python3 scripts/seed_registry.py --base-url http://localhost:8080` then the same command again | First exit 0: worker-a, created `sentiment-distilbert` and `v1` at `distilbert-base-uncased-finetuned-sst-2-english@714eb0fa89d2f80546fda750413ed43d93601a13`. Second exit 0: worker-b, model and v1 already registered with the same values, nothing overwritten. |
| 9 | PASS | `curl -sS http://localhost:8080/api/models/sentiment-distilbert/versions` | HTTP array, one object: id 1, version `v1`, artifact `distilbert-base-uncased-finetuned-sst-2-english`, revision `714eb0fa89d2f80546fda750413ed43d93601a13`, `createdAt` `2026-10-15T15:28:09.974334Z`. |
| 10 | PASS | Headed Chrome, `http://127.0.0.1:5173/models` | Page text includes `sentiment-distilbert`, `Registered model`, `v1`, and `Backend connected`. It also shows `vd-check-20261015152836-a2b8` because row 16 had already left that model. That row is a real registry record, not a stub. Screenshot: `screenshots/ui-models.png`. |
| 11 | PASS | Workspace, capability TEXT, sentence `This movie is wonderful.`, Send request | DOM text: `You 03:29 PM`, `POSITIVE (confidence 100.0%) — sentiment-analysis`, badge `Backend decision`, model `distilbert-base-uncased-finetuned-sst-2-english @ 714eb0fa`, worker `worker-b`, Eligible `2 of 2`, reason `no model requested: round-robin over all 2 worker(s)`, latency `43 ms`, footer `Backend connected`. The workspace does not render a request id. Backend log: `inference ok requestId=10c19a14-40f2-4226-bfed-fa2d992d5ade routedTo=localhost:8002 worker=worker-b model=- version=- label=POSITIVE totalMs=43` at `2026-10-15T15:29:39.541Z`. GET of that id: SUCCEEDED, `inputChars` 24, `workerEndpoint` `localhost:8002`, `workerId` `worker-b`, `httpStatus` 200, `totalMs` 44, routing `ROUND_ROBIN_ALL` chosen `localhost:8002`, both candidates `ANY_WORKER`. Screenshot: `screenshots/ui-inference.png`. Combined desktop (UI plus terminal with UTC time and SHA): `screenshots/desktop-ui-and-curl.png`. |
| 12 | PASS | POST `/api/inference` with `capability=TEXT`, `input=This movie is wonderful.`, `model=sentiment-distilbert`, `version=v1`, then GET `/api/requests/{id}` | HTTP 200 wall 0.059 s. id `d222dc6b-0776-4ae9-90bd-3ac680deb0d2`. worker `worker-a`, endpoint `localhost:8001`, label POSITIVE, score `0.9998793601989746`, `workerInferenceMs` 11.91, `totalLatencyMs` 37, `modelId` `sentiment-distilbert`, `modelVersion` `v1`. Response `routing` equals GET `routing`. Strategy `VERSION_AWARE`, eligible 2/2, chosen `localhost:8001`. Row status SUCCEEDED, httpStatus 200. One log line at `2026-10-15T15:28:22.783Z`: `routing decision requestId=d222dc6b-0776-4ae9-90bd-3ac680deb0d2 strategy=VERSION_AWARE … chosen=localhost:8001`. |
| 13 | PASS | Six more POSTs of that same versioned body, no other clients | All HTTP 200. Order worker-b `localhost:8002`, worker-a `localhost:8001`, repeating. `chosen` equals `workerEndpoint` on every row. Ids: `cd725018-30c7-4c7a-9167-98c4dddc5bb9` (0.023 s), `681ce797-4435-4bae-8bf3-03717060c0f8` (0.018 s), `b5a7c9f3-6716-488f-b27d-b1697817104f` (0.021 s), `226128e9-140e-41f6-b16d-542c0b95e37e` (0.016 s), `150e4089-c734-479f-898a-f4abce86ad60` (0.018 s), `dcabd1e2-e4d4-4476-9116-9ae130001bfb` (0.015 s). All POSITIVE. The first of the six is worker-b, not worker-a. The runbook says not to require A to start. |
| 14 | PASS | `round_robin_e2e_test.py --requests 8 --backend http://localhost:8080 --workers http://localhost:8001,http://localhost:8002` | Exit 0. 23/23. Order worker-a, worker-b, repeating. 4 of 8 each. |
| 15 | PASS | `version_dispatch_e2e_test.py --requests 4 --backend http://localhost:8080 --workers http://localhost:8001,http://localhost:8002` | Exit 0. 19/19. Unserved synthetic version rejected, no worker ran. Model `vd-check-20261015152836-a2b8` left in `platform_demo`. That is the behavior the runbook describes. The synthetic revision was not loaded. |
| 16 | PASS | `routing_decision_e2e_test.py --backend http://localhost:8080 --model sentiment-distilbert --version v1 --log /tmp/docsdemo/spring.log` | Exit 0. 38/38. One log line per checked request. Versioned example id `71054310-af9b-4cff-bac6-e60e4be75831`, chosen `localhost:8002`, stored routing equals the response. |
| 17 | PASS | Stop the rehearsal processes. Start workers, `./mvnw -B spring-boot:run`, and `npm run dev` again on the same database. Health, then seed. | After the stop, port 8080 refused connections. Restart: worker-a pid 119791, worker-b pid 119790, same revision, `predictionsServed` 0. Spring pid 120034. `worker pool: 2 worker(s), round-robin order [localhost:8001, localhost:8002]`. `Schema "public" is up to date. No migration necessary.` Datasource PostgreSQL 16.15. Health HTTP 200 `Backend is healthy`. Vite 200. Seed exit 0: already registered, same artifact and revision, not overwritten. Screenshot of that health curl and the versions curl in a terminal, with UTC time and the SHA: `screenshots/desktop-curl-health.png`. |
| 18 | BLOCKED | Docker | Not installed for this rehearsal and not invoked. The docs already say Docker is deferred and unverified. This row does not pass it. |

No screenshot in this rehearsal is a stubbed HTTP response. The models page's second card is a real row created by `version_dispatch_e2e_test.py`.

## Doc bugs

1. **Medium.** `docs/DEMO.md` step 3 tells the presenter to read the request ID from the workspace response. `AIWorkspace` renders answer, model, worker, eligible count, routing reason, latency, and status. It does not render `requestId`. The live DOM for `10c19a14-40f2-4226-bfed-fa2d992d5ade` has the label, confidence, worker, and `03:29 PM`, and no request id. The id is in the API, the log, and `GET /api/requests/{id}`.

2. **Medium.** `README.md` says that at startup the backend logs which database it is using, without the URL or credentials. `DatasourceInfoLogger` does omit the URL and the password (`product=PostgreSQL version=16.15`). Flyway, in the same startup, logged `Database: jdbc:postgresql://localhost:5432/platform_demo (PostgreSQL 16.15)`. The password was not in that line. The sentence as written is not accurate.

3. **Medium for a Linux rehearsal.** `docs/DEMO.md` gives the prepare and present commands only as PowerShell, including `mvnw.cmd` and `.venv\Scripts\python.exe`. This host cannot run them. The README bash forms of the same steps did run. The runbook says its commands were checked against repository interfaces; the flag names (`--base-url`, `--backend`, `--workers`, `--model`, `--version`, `--log`) match the scripts. The shell shown does not match the Ubuntu evidence environment.

4. **Low.** `docs/SPRINT2_CLOSEOUT.md` says the merge tree is identical to its tested head `31795af55cc7810fe0fa08963354839760ea810a`. That hash is the tree of both `1c1d932` and `2f731ba`. It is not a commit. The trees do match.

5. **Low.** The "Unified inference API" success example omits `modelId`, `modelVersion`, and `routing`. A real 200 from this SHA includes them. On `d222dc6b-0776-4ae9-90bd-3ac680deb0d2` they are `sentiment-distilbert`, `v1`, and a `VERSION_AWARE` object. The section says the sample values are illustrative of shape only.

6. **Low.** README says requests rejected with 400 or 422 do not take a round-robin turn. In `InferenceController`, 404 (`MODEL_NOT_FOUND`, `VERSION_NOT_FOUND`) and registry 503 are also returned before `workers.next()`. The 400/422 statement is true and incomplete.

7. **Low.** README says CORS allows only the React dev origin. `application.properties` defaults to `http://localhost:5173,http://127.0.0.1:5173`.

Checked and matching the code, so not bugs: health is `text/plain` and `Backend is healthy`; empty or non-http worker URL text starts with `Invalid configuration:`; `PLATFORM_WORKER_URLS` order is the round-robin order logged at startup; no failover; connect timeout 2000 ms and read timeout 30000 ms; identity cache default 30000 ms and probe timeout 2000 ms; tracking statement timeout 2 s, pause 10000 ms, Hikari connection timeout 2000 ms; `routing` on success POST and on GET, absent from `ErrorResponse`; `NOT_ROUTED` is built with an empty candidate list; candidate notes are `ANY_WORKER`, `ELIGIBLE`, `SERVES_OTHER`, `IDENTITY_UNKNOWN`; badge strings in `routingBadgeLabel` match the README; `seed_registry.py --base-url` is idempotent for the same v1 and the script's mismatch path exits 1 without writing (not re-triggered here because v1 matched); `latest` cannot be registered (`RegistryValidation`); version list SQL is `ORDER BY created_at, id`; workers and monitoring still call `/api/workers` and `/api/metrics`; `NetworkVisualization` still hardcodes "Not connected"; `npm test` is `vitest run`.

Slack permalinks in the closeout were not opened. They are not marked pass or fail.

## Recommendation

The native stack at `c818172072d4c8397a4689395a05d95dfb7b3d25` followed the demo's sequence on PostgreSQL 16, two real DistilBERT workers, Spring, and Vite. Startup, both identities, seed, registry display, real UI inference, persisted decision, alternation, versioned success, unserved-version rejection, and restart all passed in bash. Literal PowerShell and Docker are BLOCKED.

The documentation has the medium inaccuracies above. Those are doc fixes. This review did not change the code. Codex owns the fixes.

## NEXT

Codex: correct the workspace request-id instruction, the "no database URL in the startup log" sentence, and the `31795af` wording. Add the bash forms next to the PowerShell blocks if this runbook is the one the Ubuntu evidence is judged against. Do not treat Docker as verified.

## Artifacts

`/cursor/stores/self/artifacts/docs-c818172/TEST_REPORT_docs_c818172.md`

Screenshots: `screenshots/ui-inference.png`, `screenshots/ui-models.png`, `screenshots/desktop-ui-and-curl.png`, `screenshots/desktop-curl-health.png`.

Logs: `logs/spring.log`, `logs/spring-restart.log`, `logs/present.log`, `logs/seed1.log`, `logs/seed2.log`, `logs/round_robin.log`, `logs/version_dispatch.log`, `logs/routing_decision.log`, `logs/ui-rehearsal.log`, `logs/npm-ci.log`, `logs/vite.log`, `logs/vite-restart.log`, `logs/worker-a.log`, `logs/worker-b.log`.
