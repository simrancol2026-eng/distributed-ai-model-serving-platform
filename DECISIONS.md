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
