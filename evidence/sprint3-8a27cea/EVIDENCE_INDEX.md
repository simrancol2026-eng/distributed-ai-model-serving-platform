# Evidence index — Sprint 3 health-latency at 8a27cea

Review of `codex/sprint3-health-latency` at `8a27ceafa8724903d8b856cf4e80a9f83d6f6393`. The product clone was not modified and was not pushed.

The password is not in these files. Flyway's JDBC URL in the Spring logs names localhost and `s3_8a27cea` and does not include a password.

## Files

| Path | Where it came from |
| --- | --- |
| `TEST_REPORT_s3_8a27cea.md` | PASS/FAIL/BLOCKED table, comparison counts, bugs. |
| `logs/mvn-test.log` | `./mvnw -B test` with routing and database env unset. 113/113, H2 and fakes. |
| `logs/docker.txt` | `docker` missing, exit 127. |
| `logs/db-setup.log` | Role and database `s3_8a27cea` created. Public tables 0. |
| `logs/worker-a.log` / `logs/worker-b.log` | Real DistilBERT on 8011 and 8012. |
| `logs/spring-rr.log` | Default policy. `policy=ROUND_ROBIN`. Flyway V1–V3. |
| `logs/rr-one.json` | First default-policy inference, `75d38838-15c0-488a-9c5f-d93839cf6758`. |
| `logs/e2e-rr-*.log` | Sprint 2 e2e scripts on the default backend. |
| `logs/spring-hl.log` | `HEALTH_LATENCY` pointed at the real workers. |
| `logs/e2e-hl-*.log` | Same scripts on that backend. They fail the round-robin assertions. |
| `logs/spring-hl-proxy.log` | `HEALTH_LATENCY` behind the fault proxy. |
| `logs/proxy.jsonl` | Proxy events. Artificial holds, HTTP 500s, and delays are marked in the events. |
| `logs/warmup-before-probe.json` | 503 before the first probe completed. |
| `logs/health-checks.txt` | Warmup release, exclusion, readmit, t+4.4/t+5.1, identity, cooldown, saturation, in-flight, agreement. |
| `logs/stored-sample.json` | `GET /api/requests/{id}` for the checks whose HTTP bodies omit routing. |
| `logs/error-kills-poller.txt` | Compiled-class harness. An `Error` stops the scheduled probe. A `RuntimeException` does not. |
| `logs/spring-cmp-*-slow.log` | Barrier of 10 with an artificial 2 s predict delay. |
| `logs/spring-cmp-*-dead.log` | 20 staggered requests against closed port 8099. |
| `logs/compare-from-spring-logs.txt` | Counts parsed from those Spring logs. |
| `logs/spring-phase1.log` / `logs/e2e-phase1.log` / `logs/phase1_e2e_results.json` | Default one-worker phase1, 8/8. |
| `logs/npm-ci.log` / `logs/npm-build.log` | Frontend `npm ci` and `npm run build`, `FE_RC=0`. |
