# Retest: HEALTH_LATENCY repair at ed28851

Product SHA `ed288514db9a29dd03410d7bce17f7edcfae6768` on `codex/sprint3-health-latency`. Parent `61b77ba33f634c23ad7c1ec59ed16b3b2f34b16e`. `dcba236` is a partial repair and is superseded. Its evidence is labeled as such on `evidence-dcba236` at `561180e0375b5af265e1b3a11e9f9f9ec5e067ec`. This report is the measurement of `ed28851` only.

Commits since `8a27cea`: `dcba236` HealthPoller, `d401c77` InferenceController, `3729713` WorkerHealth, `74263e3` WorkerPool, `61b77ba` HealthPollerTest, `ed28851` WorkerHealthTest. Diff vs `8a27cea` is 6 files, +73/−7. Versus the partial SHA, `HealthPoller.java` is unchanged and the rest of that diff is new.

`WorkerPool.healthFreshnessNanos` is `(2 * (interval + probeTimeout) + 2000) ms`. With the defaults of 2000 ms and 2000 ms that is 10 seconds. `WorkerHealth.pollOnce` catches `Throwable` and records a failed observation. The 422 and 503 sentences in `healthyCall` are different strings.

H2 `./mvnw -B test` is separate from PostgreSQL 16 database `s3_ed28851` (empty before Flyway, left in place). Artificial faults are labeled. The mid-run stop is a real `SIGTERM`.

## Results

| Test | Result | Command | Actual output |
| --- | --- | --- | --- |
| Diff vs 8a27cea | recorded | `git diff --stat 8a27cea..ed28851` | 6 files, 73 insertions, 7 deletions |
| MED #1 freshness timeline | PASS | health retest, artificial `/health` hold on 8001 | Success at t=0 (hold set 0.027s later). Held probes arrived at 2.003s and 6.007s. At 5.135s `live=true` healthAgeMs=5105. At 7.232s still live, healthAgeMs=7204. Excluded at 8.165s `65a730a8` live=false healthAgeMs=8146, under 10s |
| LOW #2 422 vs 503 wording | PASS | same run, model `hl-422-ed28851` version v2, and the all-unhealthy 503 | 422 `3394c0fc`: `No worker serves the requested capability or model version: every worker's current health reports something else.` 503 `2584ec98`: `No live worker can serve this request right now: worker health is failing or not yet observed.` `MESSAGES_DISTINCT True` |
| MED #3 Throwable counts and the schedule continues | PASS | `java ... ErrorCountsAsFailure` compiled against this SHA | `ERROR_CALLS=6` `ERROR_FAILURES=2` `RUNTIME_CALLS=6` `RUNTIME_FAILURES=2`. After one success: failures=0 live=true. After one artificial Error: failures=1 live=true. After a second: failures=2 live=false. Compiled class, not a live worker |
| `./mvnw -B test`, routing and DB env unset | PASS | unset those variables, then `./mvnw -B test` | Tests run: 116, Failures: 0, Errors: 0, Skipped: 0. BUILD SUCCESS. Total time 12.914 s. Finished 2026-10-15T21:52:45Z. `MVN_RC=0` |
| Two fast probe failures exclude | PASS | artificial HTTP 500 on both `/health` | `2584ec98` HTTP 503 at 4.167s. Stored both live=false, healthAgeMs 4256 and 4241 |
| One success readmits | PASS | `/health` pass on 8001 only | `10b100ba` HTTP 200 in 1.807s, healthAgeMs=45. 8002 still live=false |
| Cooldown | PASS | artificial predict HTTP 500 on both | `c562145d` chose 8001; 8002 note=COOLDOWN cooldownMs=4995 live=true. Next `ab942c43` cooldownMs=4987. 8001 stayed cooldownMs=0 after one 5xx |
| In-flight release | PASS | success, artificial 502, artificial socket close, artificial predict hold | Next after success `ac8e24a6` inFlight 0. 502 `aed326f7` body has no routing; stored and one log line match, chosen 8002 inFlight 0; next `673c8b53` inFlight 0. Refuse `7eb46461` HTTP 503, next `129f461f` inFlight 0. During hold, one decision recorded inFlight 1. Negative inFlight rows: 0 |
| API response = stored JSON = log | PASS | agreement `4bd6d00c` plus the error ids above | `AGREE_EQUAL True`, one log line, policy HEALTH_LATENCY. Error HTTP bodies omit routing. Stored JSON and the one log line match for `3394c0fc`, `aed326f7`, and `7eb46461` |
| RR e2e on 8011/8012 | PASS | the three Sprint 2 scripts, policy ROUND_ROBIN, `--workers http://localhost:8011,http://localhost:8012` | 23/23, 19/19, 31/31 |
| LIF e2e on 8011/8012 | PASS | same scripts, policy LEAST_IN_FLIGHT | 23/23, 19/19, 31/31 |
| Warmed, then artificial 2.0s delay | recorded | 8 sequential successes, then barrier of 8 on 8002 | Wall 2.076s. 7 HTTP 200 on the delayed worker (totalMs 2018–2064) and 1 HTTP 200 on 8001 (totalMs 37) |
| Real SIGTERM of worker-b mid-burst | recorded | 30 requests, 0.2s apart, SIGTERM at 0.50s, each policy | RR 15/1/14, LIF 15/1/14, HL 26/3/1 (200 on 8011 / 200 on 8012 / 503). HL had one failure |

## Prior bugs

1. Medium. Fixed on this SHA. The freshness window is `2 * (2000 + 2000) + 2000` ms = 10s. An artificial hold on `/health` for 8001 produced this timeline. The successful probe completed at t=0. The next probe arrived at 2.003s and was held; the one after arrived at 6.007s. The gap of 4.004s is one probe timeout plus one poll interval. At 5.135s the worker was still live (healthAgeMs=5105, note ELIGIBLE). At 7.232s it was still live (healthAgeMs=7204). At 8.165s request `65a730a8` showed live=false, note UNHEALTHY, healthAgeMs=8146. That age is under 10 seconds, so the exclusion is the second failed observation, not the window expiring. Releasing the hold readmitted it in 1.927s (healthAgeMs=66). On the partial SHA `dcba236` the same kind of hold was already not live at 5.1s.

2. Low. Fixed on this SHA. The unserved-revision 422 and the no-live-worker 503 use different sentences. Request `3394c0fc` HTTP 422, both candidates live=true, note SERVES_OTHER, served revision `714eb0fa89d2f80546fda750413ed43d93601a13`, target revision `bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb`. Request `2584ec98` HTTP 503 used the other sentence. The partial SHA used one sentence for both.

3. Medium. Fixed on this SHA, including the failure count. `pollOnce` catches `Throwable` and stores a failed observation, and `pollSafely` still stops an exception from cancelling `scheduleWithFixedDelay`. Compiled-class harness, 200 ms interval, 1.2 s sleep, slf4j no-op logger, not a live DistilBERT process: `ERROR_CALLS=6`, `ERROR_FAILURES=2`, `RUNTIME_CALLS=6`, `RUNTIME_FAILURES=2`. A direct sequence on one observation: success leaves failures=0 and live=true; one artificial `Error` leaves failures=1 and live=true; the second leaves failures=2 and live=false. On the partial SHA the same Error shape kept running but left `ERROR_FAILURES=0`.

## Other measured checks

Two artificial `/health` HTTP 500s excluded both workers at 4.167s with healthAgeMs 4256 and 4241, under the 10s window. One success on 8001 readmitted it in 1.807s.

Cooldown, artificial predict HTTP 500: the decision is taken before the call returns, so the second 5xx still shows cooldownMs=0. The next decision, `c562145d`, showed 8002 in COOLDOWN at 4995 ms and still live. One 5xx on 8001 left cooldownMs=0.

In-flight: after success, after artificial 502 `aed326f7`, and after artificial connection close `7eb46461`, the next decision showed inFlight 0. The 502 and 503 HTTP bodies have no `routing` field. During an artificial predict hold the SQL snapshot of DISPATCHED rows showed inFlight 0; the decision logged for `0208d53f` was taken while the other call was in flight (inFlight 1). After both holds finished, the agreement decision showed inFlight 0. SQL count of `"inFlight":-` was 0. An earlier refuse aimed only at 8001 did not run, because 8002 was the worker chosen; the both-ports refuse is the 503 measurement.

Agreement, success `4bd6d00c`: response `routing` equals the stored document, and the log has one `routing decision` line with policy HEALTH_LATENCY.

### Warmed worker, then an artificial 2.0 s predict delay

Eight sequential successes pulled 8002 to ewmaMs 33.775 (samples 45). 8001 stayed at ewmaMs 198.811 (samples 10); that figure includes the earlier artificial predict hold (totalMs about 920). Barrier of 8 with artificial `delay:2.0` on 8002 only:

| request | HTTP | endpoint | totalMs | 8002 inFlight | 8002 score | 8001 score |
| --- | --- | --- | --- | --- | --- | --- |
| 1a63eff1 | 200 | 8002 | 2064 | 0 | 33.775 | 198.811 |
| a77f3279 | 200 | 8002 | 2018 | 1 | 67.549 | 198.811 |
| 8fd6c51f | 200 | 8002 | 2063 | 2 | 101.324 | 198.811 |
| 5d337c8f | 200 | 8002 | 2061 | 3 | 135.098 | 198.811 |
| 59467a48 | 200 | 8002 | 2060 | 4 | 168.873 | 198.811 |
| c04edf29 | 200 | 8001 | 37 | 5 | 202.647 | 198.811 |
| 27b8e797 | 200 | 8002 | 2048 | 5 | 202.647 | 397.621 |
| 0661a46e | 200 | 8002 | 2050 | 6 | 236.422 | 397.621 |

Wall 2.076 s. Seven calls stayed on the worker whose predict was artificially delayed, because the warmed estimate times inFlight was still the lower score. One call, `c04edf29`, went to 8001 when 8002's in-flight score crossed 8001's score. This is that burst. It is not a claim that the policy avoided the slow calls in general.

### Real SIGTERM of worker-b during 30 staggered requests

Same shape for each policy: real workers on 8011 and 8012, 30 requests 0.2 s apart, `SIGTERM` of the worker-b uvicorn pid at 0.50 s. Worker-b was started again between policies and answered `/health` 200 after 5 s. The 503s are connection refusals after the process exit.

| policy | HTTP 200 on 8011 | HTTP 200 on 8012 | HTTP 503 | log |
| --- | --- | --- | --- | --- |
| ROUND_ROBIN | 15 | 1 | 14 | last failure `456b0b3e` still chosen 8012, note ANY_WORKER |
| LEAST_IN_FLIGHT | 15 | 1 | 14 | last failure `d782bf70` still chosen 8012, note ANY_WORKER |
| HEALTH_LATENCY | 26 | 3 | 1 | `6389f51b` chosen 8012 while still live. Next `e4444e3a` note COOLDOWN, cooldownMs=4803, live=true. `c290ecb9` note UNHEALTHY, live=false, healthAgeMs=4119. Last `0ce36f3b` still UNHEALTHY, cooldownMs=0, healthAgeMs=5319 |

HEALTH_LATENCY had one failure in this window, then stopped choosing 8012. ROUND_ROBIN and LEAST_IN_FLIGHT kept choosing 8012 through the end of the burst. These counts are for this pattern only.

Sprint 2 e2e scripts were not used as the HEALTH_LATENCY acceptance test. Under ROUND_ROBIN and under LEAST_IN_FLIGHT they passed 23/23, 19/19, and 31/31. Those scripts still require alternation and ANY_WORKER on their own policies. Non-alternation under HEALTH_LATENCY was not treated as a failure.

## Stack

PostgreSQL 16 database `s3_ed28851`, role `s3_ed28851`, 0 public tables before the backend. Two DistilBERT workers, revision `714eb0fa89d2f80546fda750413ed43d93601a13`, on 127.0.0.1:8011 and 127.0.0.1:8012. HEALTH_LATENCY was pointed at the artificial proxy on 8001 and 8002. Registry create for `hl-422-ed28851` returned 201 at 2026-10-15T21:55:06.423689Z; v1 and v2 returned 201.
