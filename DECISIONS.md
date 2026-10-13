# Decision Log

This file records each technical decision, the alternatives that were considered, and the reason for the choice. It exists so every decision can be explained in the capstone defense.

Format: **D-<number> — title** · phase · who decided

---

## Phase 0 — Repository cleanup

### D-001 — Remove Git metadata committed inside `backend/spring-boot/`
Phase 0 · Claude (implementer)

- **Problem:** The files `HEAD`, `config`, `index`, `objects/`, `hooks/`, `logs/` and `refs/` were committed as ordinary files inside the backend folder. They are the internals of a separate Git repository that was copied in by accident. As a result, running `git` commands inside that folder fails with "this operation must be run in a work tree", because Git thinks the folder is its own repository.
- **Decision:** Delete these files from version control. The real source files (`src/`, `pom.xml`, `mvnw`, `.mvn/`) are untouched.
- **Alternatives considered:** Keeping them was rejected because they serve no purpose and break Git tooling. Converting the folder to a Git submodule was rejected as unnecessary complexity.

### D-002 — Add a root `.gitignore`
Phase 0 · Claude

- **Problem:** The repo had no root ignore file, so a Python cache file (`__pycache__/worker.cpython-314.pyc`) was committed.
- **Decision:** Add one root `.gitignore` that covers Python, Java/Maven, Node, build output, `.env` files, model weights and datasets. The existing per-project ignore files are kept.
- **Notes:**
  - Dataset patterns are root-anchored (`/data/`) on purpose, so that `frontend/src/data/` (source code) is not ignored.
  - `.vscode/settings.json` is kept. It holds only one harmless Java setting.

### D-003 — npm is the single frontend package manager
Phase 0 · Claude

- **Problem:** The frontend had two lockfiles, `package-lock.json` (npm) and `bun.lock` (bun), and the npm lockfile was out of sync. As a result, `npm ci`, the reproducible install that CI and reviewers use, failed.
- **Decision:**
  - Keep npm, since the student already uses it.
  - Regenerate `package-lock.json`.
  - Remove `bun.lock` and `bunfig.toml`.
- **Verification:** After deleting `node_modules`, `npm ci` succeeds.

### D-004 — Re-encode `requirements.txt` as UTF-8
Phase 0 · Claude

- **Problem:** The file was saved as UTF-16 with Windows line endings, which typically happens when PowerShell runs `pip freeze > requirements.txt`. pip happens to accept it, but many other tools (diff viewers, Docker linters, scanners) do not.
- **Decision:** Convert it to UTF-8 with LF line endings. The package list is unchanged.

### D-005 — Apply Prettier formatting as a separate commit
Phase 0 · Claude

- **Problem:** `npm run lint` reported 359 formatting errors, which hid any real lint problems.
- **Decision:** Run the project's own Prettier configuration in a commit that contains only formatting changes, so it is easy to review and verify that no logic changed. After the commit, lint reports 0 errors and 10 warnings (React fast-refresh hints, which are dev-only and do not indicate bugs).

### D-006 — Defer removing the Lovable build dependency
Phase 0 · Claude

- **Problem:** `frontend/vite.config.ts` uses `@lovable.dev/vite-tanstack-config`, the website generator's shared configuration.
- **Decision:** Leave it for now. Phase 0 must not redesign the application, and replacing the build configuration risks breaking a build that currently works. This is listed under Known issues in the README for a later cleanup.

---

## Phase 1 — First end-to-end inference path

### D-007 — Keep the existing text-sentiment worker for Phase 1
Phase 1 · Claude, within ChatGPT's plan

- **Context:** The only working model in the repo is DistilBERT text sentiment. The lung-image models are not in the repo.
- **Decision:** Prove the end-to-end path with this model, as the plan allows ("use the correct payload for the actual model").
- **Benefits:**
  - Phase 1 is not blocked waiting for the image models.
  - It demonstrates that the platform is model-agnostic. Image workers will be added next to it rather than replacing it.

### D-008 — Worker accepts a JSON body instead of a query parameter
Phase 1 · Claude

- **Before:** `POST /predict?text=...`, with the text in the URL.
- **After:** `POST /predict` with the JSON body `{"text": "..."}`.
- **Why:**
  - URLs have length limits and get logged by proxies.
  - JSON bodies are the standard for model-serving APIs.
  - JSON extends naturally to other input types later.
  - The old form had no users, so nothing breaks.

### D-009 — The worker reports only metadata it actually knows
Phase 1 · Claude

- The response includes `model`, `task` and `inferenceMs` (measured around the model call).
- It also includes `version`, which is the Hugging Face revision hash when the library exposes it, and otherwise `null`. A version string is never invented.

### D-010 — Truncate input to the model's limit
Phase 1 · Claude

- **Problem:** DistilBERT accepts at most 512 tokens. Longer text made the original worker raise an error.
- **Decision:** Call the model with `truncation=True`, so only the first 512 tokens are classified.
- **Limits:** Separately, both the API and the worker reject input over 10,000 characters.

### D-011 — Unified API shape: `{capability, input}` → normalized response with error codes
Phase 1 · Claude

- **Design:**
  - The request carries the capability, so that later phases can route by capability.
  - The response carries `worker`, `model` and the latencies, so that each request can be traced through the system.
  - Errors use stable codes (`WORKER_UNAVAILABLE` etc.), so the UI can show a clear message instead of crashing.
- **Capability check:** Even with one worker, a request for a capability no worker serves gets `422 NO_ELIGIBLE_WORKER`. This is the first, smallest piece of "find compatible workers, then route".

### D-012 — Spring `RestClient` with explicit timeouts; no extra libraries
Phase 1 · Claude

- **Timeouts:** connect 2 s, read 30 s, both configurable.
- **Without timeouts:** a hung worker would hang the backend.
- **Why RestClient:** it is part of Spring itself. WebClient and Feign would add dependencies that Phase 1 does not need.

### D-013 — CORS restricted to the React dev origins, not `*`
Phase 1 · Claude (plan requirement)

- **Allowed origins:** only `http://localhost:5173` and `http://127.0.0.1:5173`.
- **Allowed methods:** GET and POST on `/api/**`.
- **Configuration:** the list can be overridden with `PLATFORM_CORS_ORIGINS` for deployment.

### D-014 — `/api/health` stays plain text
Phase 1 · Claude

- **Rule:** The frontend treats any HTTP 200 as "connected".
- **Why:** This keeps the existing, already-verified health contract (`Backend is healthy`) unchanged. Richer structured health reporting belongs to the health-monitoring phase.

### D-015 — How Phase 1 is tested, and what each test does and does not prove
Phase 1 · Claude

| Test | Proves | Does not prove |
|---|---|---|
| Worker `pytest` (stub model) | HTTP contract, validation | That the model works |
| Worker `REAL_MODEL=1 pytest` | Real predictions, truncation | — |
| Backend JUnit tests (fake worker over HTTP) | Validation, mapping, 502/503/422 handling, CORS | Real model |
| Frontend vitest | Response → UI mapping, including errors | Network |
| `scripts/phase1_e2e_test.py` | The whole real path, plus worker-down and recovery | UI (Test E is manual) |

---

## Phase 1 — Review round 1 (after Grok's TEST REPORT on `65d404b`)

### D-016 — Architecture review: JSON inference contract confirmed; migration from `main`'s query API
Phase 1 · decided by ChatGPT (architect) · implemented by Claude

- **Trigger:** Grok's round-1 TEST REPORT flagged that the contract had changed:
  - the worker no longer accepts `POST /predict?text=...`, which is what `main` used
  - `/api/inference` is not multipart
  - `/api/health` is not JSON
- **Ruling (ChatGPT):** keep the JSON contracts:
  - `POST /api/inference` with `{capability, input}`
  - worker `POST /predict` with `{text}`
  - No owner or rubric requirement for multipart or query-string inference was supplied or found in the reviewed materials.
- **Migration from `main`:**
  - The old worker endpoint `POST /predict?text=...` is replaced by `POST /predict` with the JSON body `{"text": ...}`.
  - Old-style calls now get `422` from the worker (or `415` from the backend for multipart).
  - Nothing else in the repository called the old endpoint, so no other code changes are needed.
  - Acceptance tests must use the JSON contract, as `scripts/phase1_e2e_test.py` already does.
- **Earlier entries:** D-008, D-011 and D-014 stay as they were written. This entry records the review.

### D-017 — Validate the worker URL at startup (fail fast)
Phase 1 review · Claude

- **Problem:** `PLATFORM_WORKER_URL=` (set but empty) let Spring start, and every inference then failed with a generic `500 URI is not absolute`.
- **Decision:** `WorkerUrlValidator` runs when `WorkerClient` is created. It requires a non-blank, absolute `http`/`https` URL with a host. Otherwise the application refuses to start, with a message naming `platform.worker.url` / `PLATFORM_WORKER_URL`.
- **Why fail at startup:** a configuration mistake shows up immediately, rather than as confusing errors on every request.

### D-018 — `/api/health` is explicitly `text/plain` liveness
Phase 1 review · ruling by ChatGPT

- **Behavior:**
  - Always returns `200`, the body `Backend is healthy`, and `Content-Type: text/plain`, even when the client sends `Accept: application/json`.
  - Before this change, Spring labelled the plain-text body as JSON in that case.
- **Scope:** it only means "the backend process is up". It does **not** check worker readiness; that belongs to the health-monitoring phase.

### D-019 — Connection badge: first client render matches the server
Phase 1 review · Claude

- **Problem:** the header badge, workspace footer and routing panel share one health query. When the answer arrived before a later component hydrated, that component's first render said "Backend connected", while the server HTML said "Not connected". This is a React hydration error.
- **Fix:** `useBackendHealth` reports "not connected" until the component has mounted, then shows the live value.
- **Measured in a headless browser** (stand-in health server, 3 page loads per run):
  - before the fix: 3 hydration errors
  - after the fix: 0, on two separate runs
  - The badge still switches to "Backend connected" when the backend is up, and stays "Not connected" when it is down.

### D-020 — Concise worker validation errors
Phase 1 review · Claude

- **Problem:** FastAPI's default `422` echoed the rejected input back, so a ~20 KB text was returned in full.
- **Fix:** the worker returns `{"detail": [{"loc", "msg", "type"}]}` only. That still names the field and the reason (for example "String should have at most 10000 characters").

### D-021 — `mvnw` is executable in Git
Phase 1 review · Claude

- **Problem:** the file mode was `100644`, so `./mvnw` failed with exit code 126 on Linux and macOS.
- **Fix:** set to `100755` with `git update-index --chmod=+x`.

---

## Review round 2 (Grok's TEST REPORT on `8bfc496`)

### D-022 — Record the Trello sprint mapping; "Phase 1" is early Sprint 2
Docs · ChatGPT (from Trello) · recorded by Claude

- **Trello:**
  - **Sprint 1 – Foundation** covers worker, contract, health, Docker image and local inference.
  - **Sprint 2 – Serving** covers the Spring Boot serving layer, request tracking, registry/versions and multiple workers.
- **Implication:**
  - The end-to-end React → Spring Boot → worker path built on branch `phase-1-end-to-end-inference` is early Sprint 2 work.
  - Sprint 1 stays open until the worker Docker image is verified.
- **Naming:** D-007 to D-021 keep the label "Phase 1". It refers to that development milestone, not a Trello sprint.
- **Verification:** Grok's round-2 report on `8bfc496` passed 12/12 checks. Docker was not measured (no Docker in the test environment).

---

## Sprint 2 — model registry and versions (branch `sprint2-model-registry`)

### D-023 — PostgreSQL + Spring JdbcClient + Flyway for the registry
Sprint 2 · proposed by Claude, approved with amendments by ChatGPT

- **Choice:** plain SQL through `JdbcClient` (spring-boot-starter-jdbc). The schema lives in a versioned Flyway migration, `db/migration/V1__registry.sql`.
- **Why:**
  - The planned database is PostgreSQL.
  - Plain SQL is easy to read and defend.
  - Flyway makes schema setup reproducible on an empty database. It never re-applies a migration (`spring.flyway.clean-disabled=true`).
- **Not chosen:** JPA/Hibernate. It is more machinery than two tables need.
- **Config:** `PLATFORM_DB_URL`, `PLATFORM_DB_USER` and `PLATFORM_DB_PASSWORD` come from the environment. No credentials are committed.

### D-024 — Uniqueness is enforced by the database, and races become 409
Sprint 2 · Claude

- **Constraints:**
  - `models.id` is the primary key.
  - `UNIQUE(model_id, version)`.
  - `UNIQUE(model_id, artifact, artifact_revision)`.
  - The foreign key `model_versions.model_id` references `models.id`.
- **Why:** checking first in Java and then inserting can let two simultaneous requests both pass the check. The database rejects the second insert, and the API translates that rejection into 409.
- **Which 409:** after a duplicate-key error, the API asks the database whether the label already exists. If it does, the response is `VERSION_ALREADY_EXISTS`; otherwise it is `DUPLICATE_ARTIFACT`. This avoids parsing vendor-specific error text.
- **Aliases:** the same artifact and revision under a second label is rejected. Two labels for one artifact must not be presented as two distinct versions.

### D-025 — Full 40-hex revision; registration is metadata only
Sprint 2 · ChatGPT amendment

- **Rule:** `artifactRevision` must be the full 40-hex commit. A 7-character prefix is not guaranteed unique, so it is rejected. Uppercase input is stored as lowercase.
- **Limit:** registering a revision records metadata only. It does not prove the artifact was downloaded or executed.
- **Seed:** `scripts/seed_registry.py` takes the revision from a real inference response rather than a hand-typed value.

### D-026 — "Latest" means most recently registered
Sprint 2 · ChatGPT amendment

- **Rule:** latest = newest `created_at`, with ties broken by the higher id.
- **Not:** semantic-version order, and not "active" or "deployed". Deployment state belongs to later Sprint 2 tasks (version-aware dispatch).

### D-027 — Tests use in-memory H2; acceptance needs real PostgreSQL
Sprint 2 · ChatGPT amendment

- **Tests:** Spring tests run against H2 in PostgreSQL mode, set in `src/test/resources/config/application.properties`. That file overrides only the datasource.
- **Evidence:** H2 results test the API logic and constraint translation. They are **not** evidence of PostgreSQL behaviour or persistence.
- **Acceptance:** Grok's Spring + real PostgreSQL run of `scripts/registry_persistence_check.py`, plus its own `psql` checks.
- **Datasource log:** the backend logs the database product, version and active profiles at startup, never the URL or credentials. This lets evidence show which engine was used.

### D-028 — Database setup and the persistence check are non-destructive
Sprint 2 · ChatGPT amendment, Grok request

- **Setup:** `scripts/setup_registry_db.sh` creates the role and database only if they are missing. It never drops or alters existing ones. It reads the password with psql `\getenv`, so the password is not on any command line.
- **Persistence check:** `registry_persistence_check.py` starts its own backend, refuses to run if the port is already in use, and stops only the process group it started. It adds uniquely named synthetic rows and never deletes, truncates or drops anything. Port and database come from arguments or the environment.

### D-029 — Models page: registry data, empty state, labelled examples
Sprint 2 · ChatGPT amendment

- **Registry answers:** `GET /api/models` returns 200 with an array. The page shows those rows, or "No models registered yet" when the array is empty.
- **Registry unavailable:** any other response, or no backend, means unavailable. The page says so and shows the static catalog with the status "Example only" and the footer "example entries".
- **Never:** a failure is never shown as registered models.
- **List fields:** `workers` is `null` (not tracked yet). `status` is `REGISTERED`, meaning recorded, not running.

### D-030 — Review fixes on `6f1cedc` (Codex code review)
Sprint 2 · Codex finding · fixed by Claude

1. **Seed script:**
   - **Problem:** `seed_registry.py` treated any 409 as success, so a pre-existing `v1` pointing at a different revision was silently accepted.
   - **Fix:** on a duplicate, the script reads the existing model or version back and compares it. A match means a rerun: exit 0. A mismatch is reported: exit 1, nothing overwritten. Any other failure exits 1.
   - **Tests:** `scripts/tests/test_seed_registry.py`, 8 cases against an in-memory fake API.
2. **Models dialog:**
   - **Problem:** the open detail dialog used the page's *current* data source for its label. After a refresh from offline to the real registry, an example entry could be labelled "Registered model".
   - **Fix:** the selected row now stores whether it came from the registry, and the dialog uses only that.
3. **README:** the React status row now separates the earlier inference evidence from this branch's Models page.
4. **Persistence check:** the datasource line comes from an `ApplicationRunner`, which can run just after `/api/health` first answers. The script now waits up to 20 s for that line, so a startup race is not misread as "not PostgreSQL".
