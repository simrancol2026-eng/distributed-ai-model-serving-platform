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
