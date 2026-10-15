# Evidence index for ed28851

Product commit `ed288514db9a29dd03410d7bce17f7edcfae6768`, parent `61b77ba33f634c23ad7c1ec59ed16b3b2f34b16e`. Report: `TEST_REPORT_s3_ed28851.md`.

`dcba236` is a partial, superseded repair. Its labeled evidence is branch `evidence-dcba236` at `561180e0375b5af265e1b3a11e9f9f9ec5e067ec`.

| Path | What it is |
| --- | --- |
| `logs/diff-vs-8a27cea.patch` | Full diff since the original HEALTH_LATENCY SHA |
| `logs/diff-stat-vs-8a27cea.txt` | 6 files, +73/−7 |
| `logs/diff-stat-vs-dcba236.txt` | Diff against the partial repair |
| `logs/commits-since-8a27cea.txt` | Six commits, ending at ed28851 |
| `logs/mvn-test.log` | `./mvnw -B test` with routing and DB env unset. 116 tests, BUILD SUCCESS |
| `logs/db-setup.log` | Empty database `s3_ed28851`. No password |
| `logs/error-counts-as-failure.txt` | Compiled-class harness. `ERROR_CALLS=6` `ERROR_FAILURES=2` |
| `harness/src/ErrorCountsAsFailure.java` | Harness source. Artificial `Error`, then artificial `RuntimeException` |
| `logs/spring-rr.log` | ROUND_ROBIN on real workers 8011 and 8012 |
| `logs/e2e-rr-round_robin.log` | 23/23 |
| `logs/e2e-rr-version_dispatch.log` | 19/19 |
| `logs/e2e-rr-routing_decision.log` | 31/31 |
| `logs/spring-lif.log` | LEAST_IN_FLIGHT on 8011 and 8012 |
| `logs/e2e-lif-round_robin.log` | 23/23 |
| `logs/e2e-lif-version_dispatch.log` | 19/19 |
| `logs/e2e-lif-routing_decision.log` | 31/31 |
| `logs/spring-hl.log` | HEALTH_LATENCY behind the artificial proxy |
| `logs/proxy.log` | Proxy JSON lines, including arrival times. Modes are artificial |
| `logs/health-retest.txt` | Freshness timeline, exclusion, readmit, 422, cooldown, in-flight, agreement, warmed load |
| `logs/inflight-refuse-release.txt` | Artificial socket close, then the next decision |
| `logs/spring-kill-rr.log` `logs/spring-kill-lif.log` `logs/spring-kill-hl.log` | Real SIGTERM runs |
| `logs/kill-rr.txt` `logs/kill-lif.txt` `logs/kill-hl.txt` | Per-request rows and counts |
| `logs/worker-a.log` `logs/worker-b.log` `logs/wa-health.json` `logs/wb-health.json` | Real workers |
| `logs/worker-b-restart.log` | worker-b started again between policies |
| `harness/health_retest.py` `harness/kill_midrun.py` `harness/fault_proxy.py` | Harnesses. They do not read the database password |

`spring.sh` is not in this tree. It reads the database password from a file outside the repo.
