# Evidence index for dcba236

Product commit `dcba236543e55315f529d68e7b94a1d3a27744c8`, parent `8a27ceafa8724903d8b856cf4e80a9f83d6f6393`. Report: `TEST_REPORT_s3_dcba236.md`.

| Path | What it is |
| --- | --- |
| `logs/diff-vs-8a27cea.patch` | Full diff. Only `HealthPoller.java` |
| `logs/diff-stat.txt` | `1 file changed, 14 insertions(+), 1 deletion(-)` |
| `logs/mvn-test.log` | `./mvnw -B test` with routing and DB env unset. 113 tests, BUILD SUCCESS |
| `logs/db-setup.log` | Empty database `s3_dcba236` created. No password |
| `logs/error-keeps-poller.txt` | First compiled-class harness run (slf4j-api 1.7.36 NOP). `ERROR_CALLS=6` |
| `logs/error-keeps-poller-slf4j2.txt` | Same harness on the product classes with slf4j-api 2.0.18. `ERROR_CALLS=6` `ERROR_FAILURES=0` `RUNTIME_CALLS=6` `RUNTIME_FAILURES=2` |
| `harness/src/ErrorKeepsScheduledTask.java` | Harness source. Artificial `Error`, then artificial `RuntimeException` |
| `logs/spring-rr.log` | Default ROUND_ROBIN on real workers 8011 and 8012 |
| `logs/e2e-rr-round_robin.log` | 23/23 |
| `logs/e2e-rr-version_dispatch.log` | 19/19 |
| `logs/e2e-rr-routing_decision.log` | 31/31 |
| `logs/spring-lif.log` | LEAST_IN_FLIGHT on 8011 and 8012 |
| `logs/e2e-lif-round_robin-default-port-8001.log` | Mis-invoked against the script default port 8001. Connection refused. Not a product failure |
| `logs/e2e-lif-version_dispatch-default-port-8001.log` | Same mis-invocation |
| `logs/e2e-lif-round_robin.log` | 23/23 on 8011 and 8012 |
| `logs/e2e-lif-version_dispatch.log` | 19/19 |
| `logs/e2e-lif-routing_decision.log` | 31/31 |
| `logs/spring-hl.log` | HEALTH_LATENCY behind the artificial proxy |
| `logs/proxy.log` | Proxy JSON lines. Modes are artificial |
| `logs/health-retest.txt` | Exclusion, readmit, slow probe, 422, cooldown, in-flight, agreement, warmed load |
| `logs/inflight-502-release.txt` | Artificial predict HTTP 500, then the next decision. `e797ac3c` then `a33049ea` |
| `logs/spring.log` | Failed harness start: the worker URL was passed as the policy name. BUILD FAILURE, no counts |
| `logs/spring-kill-rr.log` | ROUND_ROBIN during the real SIGTERM |
| `logs/spring-kill-lif.log` | LEAST_IN_FLIGHT during the real SIGTERM |
| `logs/spring-kill-hl.log` | HEALTH_LATENCY during the real SIGTERM |
| `logs/kill-rr.txt` `logs/kill-lif.txt` `logs/kill-hl.txt` | Per-request rows and counts |
| `logs/worker-a.log` `logs/worker-b.log` `logs/wa-health.json` `logs/wb-health.json` | Real workers before the kill runs |
| `logs/worker-b-restart.log` | worker-b started again between policies |
| `harness/health_retest.py` `harness/kill_midrun.py` `harness/fault_proxy.py` | Harnesses. They do not read the database password |

`spring.sh` is not in this tree. It reads the database password from a file outside the repo.
