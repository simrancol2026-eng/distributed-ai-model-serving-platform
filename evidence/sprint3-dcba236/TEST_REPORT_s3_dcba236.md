# Retest: HEALTH_LATENCY repair at dcba236

Product SHA `dcba236543e55315f529d68e7b94a1d3a27744c8` on `codex/sprint3-health-latency`. Parent `8a27ceafa8724903d8b856cf4e80a9f83d6f6393`. This report is only that SHA.

Diff vs parent is one file: `HealthPoller.java`, 14 insertions, 1 deletion (`logs/diff-stat.txt`, `logs/diff-vs-8a27cea.patch`). `scheduleWithFixedDelay` now calls `pollSafely`, which catches `Throwable` and logs `worker health poll failed; will retry on the next interval`. `WorkerHealth`, `HealthRouting`, `InferenceController`, and the tests are unchanged.

H2 `./mvnw -B test` is separate from the PostgreSQL 16 stack. Artificial faults (proxy hold, HTTP 500, socket close, `delay:2.0`, and the harness `Error`) are labeled. The mid-run worker stop is a real `SIGTERM` of the uvicorn process. Database `s3_dcba236` was empty before Flyway and is left in place.

## Results

| Test | Result | Command | Actual output |
| --- | --- | --- | --- |
| Diff vs 8a27cea | recorded | `git diff --stat 8a27ceafa8724903d8b856cf4e80a9f83d6f6393 dcba236543e55315f529d68e7b94a1d3a27744c8` | `HealthPoller.java \| 15 ++++++++++++++-`, 1 file changed, 14 insertions(+), 1 deletion(-) |
| MED #1 slow probe vs 5s window | FAIL | `python3 /tmp/s3dc-run/health_retest.py` (section slow probe; artificial `/health` hold on port 8001) | `b155abb4` at dt=4.469s live=true healthAgeMs=4449; `1db890a7` at dt=5.145s live=false healthAgeMs=5148 note=UNHEALTHY; `b381bd8e` at dt=7.044s live=false healthAgeMs=7048; after release `9ae45497` live=true healthAgeMs=289 in 0.338s |
| LOW #2 unserved-revision 422 wording | FAIL | same script, model `hl-422-dcba` version v2 | HTTP 422 `803fa574` error=NO_ELIGIBLE_WORKER message=`No eligible worker has usable current health for this request.` Stored: both candidates live=true note=SERVES_OTHER, target revision `aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa` |
| MED #3 Error in a probe | PASS | `java -cp target/classes:harness/classes:slf4j-api-2.0.18.jar com.dhir.spring_boot.inference.ErrorKeepsScheduledTask` | `ERROR_CALLS=6` `ERROR_FAILURES=0` `RUNTIME_CALLS=6` `RUNTIME_FAILURES=2` `HARNESS_RC=0`. Compiled class, not a live worker. See residual under bug 3 |
| `./mvnw -B test`, routing and DB env unset | PASS | `unset PLATFORM_WORKER_URL PLATFORM_WORKER_URLS PLATFORM_ROUTING_POLICY PLATFORM_DB_URL PLATFORM_DB_USER PLATFORM_DB_PASSWORD PLATFORM_BACKEND_PORT; ./mvnw -B test` | `Tests run: 113, Failures: 0, Errors: 0, Skipped: 0` BUILD SUCCESS Total time 13.988 s Finished at 2026-10-15T21:30:33Z `MVN_RC=0` |
| Two probe failures exclude a worker | PASS | same health script; artificial HTTP 500 on both `/health` | `9b5cc735` HTTP 503 NO_HEALTHY_WORKER at 3.034s. Stored both live=false healthAgeMs=4121 and 4122 |
| One success readmits | PASS | artificial `/health` pass on 8001 only | `ff559e45` HTTP 200 in 2.077s. 8001 live=true healthAgeMs=178; 8002 still live=false healthAgeMs=6192 |
| Cooldown | PASS | artificial predict HTTP 500 on both proxy ports | Decisions are taken before the call. After two 5xx on 8002, `22f946ce` chose 8001, 8002 note=COOLDOWN cooldownMs=4993 live=true. Next `baaf215f` cooldownMs=4982. 8001 stayed cooldownMs=0 after a single 5xx |
| In-flight release | PASS | success, artificial 502, artificial socket close, artificial predict hold | After success `c31c6a84` inFlight 0. After 502 `e797ac3c` (chosen 8002) the next `a33049ea` inFlight 0. After 503 `192e79a6` the next `4bbdfa74` inFlight 0. During hold, DISPATCHED rows showed `"inFlight":1` and `"inFlight":0`. `NEGATIVE_INFLIGHT_ROWS 0` |
| API response = stored JSON = log | PASS | agreement request plus the error requests above | Success `f9ef3d21`: response routing == stored, one log line, policy=HEALTH_LATENCY. Error HTTP bodies omit routing. Stored JSON and the one `routing decision requestId=` line match for `803fa574`, `9b5cc735`, and `e797ac3c` |
| RR e2e scripts, policy ROUND_ROBIN, workers 8011 and 8012 | PASS | `python3 scripts/round_robin_e2e_test.py --backend http://localhost:8080 --workers http://localhost:8011,http://localhost:8012`; `version_dispatch_e2e_test.py` with the same workers; `routing_decision_e2e_test.py --backend http://localhost:8080 --log logs/spring-rr.log` | 23/23, 19/19, 31/31. `RR_RC=0` `VD_RC=0` `RD_RC=0`. Backend log: `policy=ROUND_ROBIN` |
| LIF e2e scripts, policy LEAST_IN_FLIGHT, same real workers | PASS | same three scripts; routing_decision `--log logs/spring-lif.log` | 23/23, 19/19, 31/31. `RR_RC=0` `VD_RC=0` `RD_RC=0`. Backend log: `policy=LEAST_IN_FLIGHT` |
| LIF scripts pointed at the script default ports 8001 and 8002 | not a product result | first invocation, no `--workers` | `Connection refused` on `http://localhost:8001/health`. The backend was on 8011 and 8012. The rerun above is the result |
| Warmed then loaded worker | recorded | health script, 8 sequential successes, then artificial `delay:2.0` on 8002 predict, barrier of 8 | Wall 2.091s. 8/8 HTTP 200, all chosen localhost:8002, totalMs 2071–2082. Decision-time 8002 ewmaMs=11.619 samples=27; inFlight 0..7 scores 11.62–92.96, all under 8001 score 334.94. Not a speedup result |
| Worker killed mid-run | recorded | `python3 kill_midrun.py <worker-b pid>` ; 30 requests, 0.2s apart, real SIGTERM at 0.50s | RR 15×200 on 8011, 1×200 on 8012, 14×503. LIF the same 15 / 1 / 14. HL 26×200 on 8011, 3×200 on 8012, 1×503. Counts only; HL did not have zero failures |

## Prior bugs

1. Medium. Not fixed. One artificial slow `/health` probe still drops the worker out of the live set once the last success is older than 5 seconds. `WorkerPool` still constructs `WorkerHealth` with `5_000_000_000L` nanoseconds, and the poll interval is still 2000 ms. The diff does not touch that. Measured on the artificial hold: live at 4.469s (healthAgeMs=4449) and not live at 5.145s (healthAgeMs=5148, note UNHEALTHY) and at 7.044s (healthAgeMs=7048). At 4.469s it was still live, so the failure counter had not already reached 2. Releasing the hold readmitted it in 0.338s (healthAgeMs=289). The other worker served those requests, so the HTTP status stayed 200.

2. Low. Not fixed. The 422 for a revision nobody serves still says `No eligible worker has usable current health for this request.` `InferenceController.healthyCall` still uses that sentence for both `NO_ELIGIBLE_WORKER` (HTTP 422) and `NO_HEALTHY_WORKER` (HTTP 503). Request `803fa574`: HTTP 422, both stored candidates live=true, note SERVES_OTHER, served revision `714eb0fa89d2f80546fda750413ed43d93601a13`. The non-health 422 wording (`No worker currently serves model ...`) is a different branch and was not this request.

3. Medium. Fixed for the scheduled task. `pollSafely` catches `Throwable`, so `scheduleWithFixedDelay` keeps the probe. Compiled-class harness (interval 200 ms, sleep 1200 ms, slf4j NOP, not a live DistilBERT process): `ERROR_CALLS=6` and `RUNTIME_CALLS=6`. On the parent SHA the same shape of harness stopped at `ERROR_CALLS=1`. Residual: an `Error` still does not increment `WorkerHealth.failures` (`ERROR_FAILURES=0`, `ERROR_LIVE=false` only because there was never a success). `pollOnce` still catches `RuntimeException` only; the `Error` leaves `pollOnce` and is swallowed by `pollSafely`. A `RuntimeException` probe still counts (`RUNTIME_FAILURES=2`). `HealthPollerTest` was not updated. Live worker I/O throws `RuntimeException`, so the live stack does not demonstrate an `Error`.

## Other measured checks

Two artificial `/health` HTTP 500s excluded both workers at 3.034s while healthAgeMs was 4121 and 4122, under 5000. One following success on 8001 readmitted that worker in 2.077s (healthAgeMs=178). The other stayed excluded.

Cooldown, artificial predict HTTP 500: the decision that receives the second 5xx still shows cooldownMs=0, because the decision is taken before the call returns. The next decision showed 8002 in COOLDOWN at 4993 ms, still live=true. One 5xx on 8001 left cooldownMs=0.

In-flight: the next decision after a success, after artificial 502 `e797ac3c`, and after artificial connection close `192e79a6` showed inFlight 0. The 502 HTTP body has no `routing` field; stored JSON and one log line do, chosen localhost:8002, inFlight 0 at decision time. During an artificial predict hold, two DISPATCHED rows were `inFlight` 0 and `inFlight` 1. After the hold was released both calls returned HTTP 200 (totalMs 887). SQL count of `routing_decision` values containing `"inFlight":-` was 0. An earlier script step labeled AFTER_502 was HTTP 200, because the worker that was actually chosen was not the one returning 500. The 502 release is `e797ac3c` then `a33049ea`.

Agreement, success `f9ef3d21`: response `routing` equals the stored document, and the log has one line `routing decision requestId=f9ef3d21-1be8-45b3-a8e3-c109426d7163 ... policy=HEALTH_LATENCY ... chosen=localhost:8002`.

### Warmed worker, then an artificial 2.0 s predict delay

Eight sequential successes on localhost:8002 left samples at 26 (ewmaMs about 11.9). The following barrier of 8, with artificial `delay:2.0` on 8002 predict only, all decided before those delayed calls returned:

| request | HTTP | endpoint | totalMs | 8002 inFlight at decision | 8002 score | 8001 score |
| --- | --- | --- | --- | --- | --- | --- |
| d562b2cb | 200 | 8002 | 2082 | 0 | 11.619 | 334.940 |
| 02bf695b | 200 | 8002 | 2081 | 1 | 23.239 | 334.940 |
| 378148c6 | 200 | 8002 | 2071 | 2 | 34.858 | 334.940 |
| f457970d | 200 | 8002 | 2072 | 3 | 46.478 | 334.940 |
| 5a8b7075 | 200 | 8002 | 2073 | 4 | 58.097 | 334.940 |
| cc348d3a | 200 | 8002 | 2076 | 5 | 69.717 | 334.940 |
| 3c4211f2 | 200 | 8002 | 2077 | 6 | 81.336 | 334.940 |
| b59cb0cc | 200 | 8002 | 2074 | 7 | 92.956 | 334.940 |

Wall 2.091 s. 8001's ewmaMs of 334.940 (samples 12) includes the earlier artificial predict hold (two calls, totalMs 887). Every delayed call still chose 8002, because `(inFlight+1) * 11.619` stayed below 334.940. The next decision after those samples were recorded, `a66a4962` at 2026-10-15T21:36:46.544Z, shows 8002 ewmaMs=1725.493 samples=35 sampleAgeMs=54786. That sample was older than 30 s, so estimateMs fell back to 100 and the choice was 8001. This table is the burst that was measured. It is not a claim that HEALTH_LATENCY avoided the slow call.

### Real SIGTERM of worker-b during 30 staggered requests

Same shape for each policy: real workers on 8011 (worker-a) and 8012 (worker-b), 30 requests 0.2 s apart, `SIGTERM` of the worker-b uvicorn pid at 0.50 s. Worker-b was started again between policies and answered `/health` 200 after 5 s. The first RR attempt shifted `spring.sh` arguments, so the worker URL was passed as the policy. `logs/spring.log` is that failed start: `No enum constant ... Policy.http://localhost:8011,http://localhost:8012`, BUILD FAILURE, no request counts. The table is the completed rerun with `PLATFORM_ROUTING_POLICY=ROUND_ROBIN`.

| policy | HTTP 200 on 8011 | HTTP 200 on 8012 | HTTP 503 | what the log showed for the failures |
| --- | --- | --- | --- | --- |
| ROUND_ROBIN | 15 | 1 | 14 | `51a19474` and the last failure `83d1a11f` both chosen localhost:8012, note ANY_WORKER |
| LEAST_IN_FLIGHT | 15 | 1 | 14 | `0939e9e5` and the last failure `9148f33e` both chosen localhost:8012, note ANY_WORKER |
| HEALTH_LATENCY | 26 | 3 | 1 | `b9ad30f0` chosen 8012 while it was still live. Next `95ed4c21` note COOLDOWN, cooldownMs=4805, live=true. By `46fd0b31` note UNHEALTHY, live=false, healthAgeMs=6312. Last `b7f2e4e8` still UNHEALTHY, cooldownMs=0 |

The 503s are real connection refusals after the process exit (`I/O error on POST ... /predict: null`). HEALTH_LATENCY had one failure in this window, then stopped choosing 8012, first by the transport cooldown and later because the health observation was not live. ROUND_ROBIN and LEAST_IN_FLIGHT kept choosing 8012 through the end of the 5.8 s burst. These are the counts for this pattern. They are not a general zero-failure claim.

Sprint 2 e2e scripts were not used as the HEALTH_LATENCY acceptance test. Under ROUND_ROBIN and under LEAST_IN_FLIGHT, with `--workers http://localhost:8011,http://localhost:8012`, they passed 23/23, 19/19, and 31/31. Those scripts still assert alternation and ANY_WORKER; that is their own policies.

## Stack

PostgreSQL 16 database `s3_dcba236`, role `s3_dcba236`, 0 public tables before the backend. Flyway applied V1, V2, and V3. Two DistilBERT workers, revision `714eb0fa89d2f80546fda750413ed43d93601a13`, on 127.0.0.1:8011 and 127.0.0.1:8012. The HEALTH_LATENCY process was pointed at the artificial proxy on 8001 and 8002, which forwarded to those workers. Registry create for `hl-422-dcba` returned 201 at 2026-10-15T21:35:20.515498Z; v1 and v2 returned 201.
