# Evidence index — Sprint 2 docs correction review at 4e2e0a0

This review executed the five new bash fences in `docs/DEMO.md` at `4e2e0a0e88038cc4104bf89294853839b3ab303d`. It did not rerun the c818172 rehearsal and it did not change app code.

Stack: fresh PostgreSQL 16.15 database `docs_4e2e` (empty before Flyway), two real DistilBERT workers at revision `714eb0fa89d2f80546fda750413ed43d93601a13` from the venv the worker-A fence created, Spring, and Vite. No Docker. `docker` was not on PATH.

The password was supplied from the environment and is not in these files. Flyway's JDBC URL in `logs/spring.log` names localhost and `docs_4e2e` and does not include a password.

## Files

| Path | Where it came from |
| --- | --- |
| `TEST_REPORT_docs_4e2e0a0.md` | This review. FIXED/OPEN table for the earlier findings, and PASS/FAIL/BLOCKED for each new bash fence. |
| `logs/worker-a.log` | Worker A fence: venv, pip, uvicorn on port 8001. `VENV_RC=0`, `PIP_RC=0`. Later SIGTERM, `UVICORN_RC=143`. |
| `logs/worker-b.log` | Worker B fence: uvicorn on port 8002, pid 122280. Later `UVICORN_RC=143`. |
| `logs/spring.log` | Backend fence on `docs_4e2e`. Flyway V1–V3, JDBC URL line, datasource line without a URL, routing decision for `5bb3a507-d6fb-4edc-a4c3-623368174526`. Later `MVNW_RC=143`. |
| `logs/vite.log` | Frontend fence: `npm ci` exit 0, Vite ready on port 8081 after "Port 8080 is in use". Later `VITE_RC=143`. |
| `logs/vite-ports.log` | Probe after the frontend fence, not part of the fence: port 5173 connection refused, port 8081 HTTP 200. |
| `logs/checks.log` | Checks-and-seed fence. Health, both worker identities, seed create, versions, versioned POST, GET, and six more POSTs. Wrapper exit 0. |
