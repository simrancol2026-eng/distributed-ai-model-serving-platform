# Evidence index — Sprint 2 docs delta at 9fe2c77

Review of `codex/sprint2-docs-demo` at `9fe2c77878c856eccb8f20ff0b56881180f70093`, compared with `4e2e0a0e88038cc4104bf89294853839b3ab303d`. Docs only. The product clone was not modified and was not pushed.

The password is not in these files. Flyway's JDBC URL in `logs/spring.log` names localhost and `docs_9fe2c77` and does not include a password.

## Files

| Path | Where it came from |
| --- | --- |
| `TEST_REPORT_docs_9fe2c77.md` | Check table: diff, Docker status row, Vite 5173 plus CORS inference, occupied-port `--strictPort`. |
| `logs/diff-stat.txt` | `git diff --stat` against `4e2e0a0e88038cc4104bf89294853839b3ab303d`. |
| `logs/diff-name-status.txt` | `git diff --name-status` for that range. |
| `logs/diff-names.txt` | Name-only list captured with the run, plus the full compare SHA. |
| `logs/diff-docs.patch` | Full patch of `README.md` and `docs/DEMO.md`. |
| `logs/diff-app-code.txt` | App-code path filter. Empty. |
| `logs/db-setup.log` | Role and database `docs_9fe2c77` created. Public tables 0 before Spring. |
| `logs/worker-a.log` | DistilBERT worker on `127.0.0.1:8001`. |
| `logs/spring.log` | `./mvnw -B spring-boot:run` on port 8080. Flyway V1–V3. Inference lines for `cb153821-5340-4413-9dcb-3d02ccb0804d` and `2afc063c-546b-4217-afb4-f0412a0e60f1`. |
| `logs/npm-ci.log` | `npm ci`, `NPM_CI_RC=0`. |
| `logs/frontend-setup.log` | Documented `cd frontend`, `npm ci`, `cp .env.example .env.local`. Example value `VITE_API_BASE_URL=http://localhost:8080`. |
| `logs/vite.log` | `vite dev --port 5173 --strictPort`. `Local: http://localhost:5173/`. |
| `logs/vite-headers.txt` | `HTTP/1.1 200` from `http://localhost:5173/`. |
| `logs/vite-index.html` | Body of that fetch, 55140 bytes. |
| `logs/cors-headers.txt` | POST `/api/inference` response headers. `Access-Control-Allow-Origin: http://localhost:5173`. |
| `logs/cors-body.json` | That POST body. `requestId` `cb153821-5340-4413-9dcb-3d02ccb0804d`, `POSITIVE`. |
| `logs/cors-options.txt` | OPTIONS preflight, HTTP 200, allow origin `http://localhost:5173`. |
| `logs/ui-shot.log` | `UI_SHOT_OK` and `UI_RC=0`. |
| `logs/strictport-occupied.log` | Second `npm run dev -- --port 5173 --strictPort` while 5173 was taken. `Error: Port 5173 is already in use`. `STRICT_RC=1`. |
| `screenshots/ui-inference.png` | Playwright screenshot after the UI inference. |
| `screenshots/ui-inference.txt` | DOM text of that page. |
