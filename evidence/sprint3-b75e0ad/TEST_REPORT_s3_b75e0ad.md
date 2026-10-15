# TEST REPORT — Sprint 3, SHA b75e0ade91aacdcfb15312e2850e82c86bdf4a8d

Independent test of `codex/sprint3-least-in-flight` at exact SHA `b75e0ade91aacdcfb15312e2850e82c86bdf4a8d` (subject `test: accept additive policy and inFlight decision fields`). Fresh clone `/tmp/s3-b75e0ad`, detached at that SHA. `60644a168ddcdc5470fd32830425741650bc4e79` was not executed. No product code was changed.

Unit and H2 results are separate from the native PostgreSQL + DistilBERT stack. BLOCKED is not PASS.

## Result table

| test | PASS/FAIL/BLOCKED | command | actual output |
| --- | --- | --- | --- |
| Full Maven suite (H2) | PASS | `cd /tmp/s3-b75e0ad/backend/spring-boot && env -u PLATFORM_WORKER_URL -u PLATFORM_WORKER_URLS -u PLATFORM_ROUTING_POLICY -u PLATFORM_DB_URL ./mvnw -B test` | `Tests run: 92, Failures: 0, Errors: 0, Skipped: 0`. `BUILD SUCCESS`. `MVN_RC=0`. Total time 10.310 s. Finished 2026-10-15T19:06:09Z. Log `logs/mvn-test.log`. |
| `LeastInFlightTest` held-concurrency, idle-tie, eligibility, busy-worker, RR, tracking-exception | PASS | same Maven run | Class `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.039 s`. Methods: `rejectsUnknownPolicyBeforeCreatingClients`, `rotatesIdleTiesAndCloseIsIdempotent`, `busyWorkerIsAvoidedButEligibilityCannotBeWidened`, `roundRobinStillAlternatesEvenWhenOneWorkerIsBusy`, `controllerReleasesReservationWhenTrackingThrowsUnexpectedly`, `simultaneousHeldReservationsAreBalancedAndReleased`. The exception-release test is in-process H2, not a live worker. |
| `RoutingDecisionApiTest` (H2) | PASS | same | `Tests run: 5, Failures: 0`. The assertions now expect `"policy":"ROUND_ROBIN"` and `"inFlight":0`. |
| Other H2 classes | PASS | same | Failures 0: `RegistryApiTest` 14, `ApplicationTests` 1, `VersionedInferenceApiTest` 7, `RoutingDecisionLogInjectionTest` 1, `WorkerDownTest` 1, `WorkerPoolTest` 7, `WorkerUrlValidatorTest` 8, `InferenceApiTest` 9, `RoutingDecisionTest` 6, `RequestTrackingPauseTest` 1, `RequestTrackingApiTest` 8, `WorkerModelMismatchTest` 1, `RoundRobinApiTest` 4, `StalledWorkerTest` 2, `OneOfTwoWorkersDownTest` 1, `TrackingGateTest` 8, `RequestTrackerSuppressionTest` 2. |
| Default policy ROUND_ROBIN (PostgreSQL) | PASS | `./mvnw -B spring-boot:run` with `PLATFORM_ROUTING_POLICY` unset, DB `s3_b75e0ad` | `spring-rr1.log`: `worker pool: 1 worker(s), policy=ROUND_ROBIN order [localhost:8001]`. Empty schema, Flyway `Successfully applied 3 migrations ... now at version v3 (execution time 00:00.018s)`. Later two-worker start: `policy=ROUND_ROBIN order [localhost:8001, localhost:8002]`, schema already at 3. |
| phase1 e2e | PASS | `scripts/phase1_e2e_test.py` with `/tmp/worker-venv/bin/python`, cwd `/tmp/s3b-run` | `8/8 checks passed`. `PHASE1_RC=0`. F1 HTTP 503 `WORKER_UNAVAILABLE` requestId `5d0c89f1-6ded-4576-8b50-6af4417fae35`. F2 recovery 4.1 s. Blank 400, IMAGE 422. CORS `Allow-Origin=http://localhost:5173`. |
| round_robin e2e | PASS | `scripts/round_robin_e2e_test.py --workers http://localhost:8001,http://localhost:8002` | `23/23 checks passed`. `RR_RC=0`. |
| version_dispatch e2e | PASS | `scripts/version_dispatch_e2e_test.py` | `19/19 checks passed`. Model `vd-check-20261015190758-611a` left in the registry. v2 synthetic revision `06edb04685a5aeecb3d3a4329af55a3e9b4b31f1` returned 422 and no worker ran. |
| routing_decision e2e under ROUND_ROBIN | PASS | `scripts/routing_decision_e2e_test.py --model sentiment-distilbert --version v1 --log spring-rr2.log` | `38/38 checks passed`. `RD_RC=0`. |
| Unknown policy fails fast | PASS | `PLATFORM_ROUTING_POLICY=NOT_A_POLICY` on port 8091 | `FAILFAST_UNKNOWN_RC=1`. Total time 1.725 s. `No enum constant ...Policy.NOT_A_POLICY`. Port 8091 was not left listening. |
| Wrong-case policy fails fast | PASS | `PLATFORM_ROUTING_POLICY=least_in_flight` | `FAILFAST_WRONG_RC=1`. Total time 1.754 s. `No enum constant ...Policy.least_in_flight`. |
| LEAST_IN_FLIGHT startup | PASS | `PLATFORM_ROUTING_POLICY=LEAST_IN_FLIGHT` | `spring-lif.log`: `worker pool: 2 worker(s), policy=LEAST_IN_FLIGHT order [localhost:8001, localhost:8002]`. |
| Sequential LIF alternation | PASS | six unversioned, then six versioned posts | Both sequences `8001,8002,8001,8002,8001,8002`. `strategy` stayed `ROUND_ROBIN_ALL` or `VERSION_AWARE`. `policy=LEAST_IN_FLIGHT`. Each snapshot `inFlight` 0/0. stored routing equals the response. One log line each. First unversioned `5f3263d4-d9fa-419a-8071-661217cfcfb6` totalMs 87 worker-a. |
| Eligibility | PASS | unserved v2, then a 3-worker pool on port 8092 with localhost:8003 closed | Unserved `093a2fbf-13ff-4580-bb44-20cfef238eeb` HTTP 422, one log line. Three-worker `edb073b9-1733-4976-af51-70b13ee7eade` HTTP 200, chosen `localhost:8001`, eligible 2 of 3, `localhost:8003` `IDENTITY_UNKNOWN` eligible false `inFlight` 0, stored equals response. Unserved `7663beae-81dc-4990-8cf5-fd3b69b7cd7d` HTTP 422, chosen null, 8001 and 8002 `SERVES_OTHER`, 8003 `IDENTITY_UNKNOWN`. |
| Status codes, no retry | PASS | 400, 404, 422, 503, 502 | Blank 400 `30b18d15-2697-4d77-a6d4-645f05b93524`. Unknown model 404 `01a9be81-2ace-41be-9f8a-7a83dc2b8e11`. IMAGE 422 `e8f75a0c-3de1-4ff4-aee6-38d3c31df4a7`. Worker A stopped: 503 `6bbf8361` totalMs 5 and `6524d49b` totalMs 3, chosen `localhost:8001`, one line each. Versioned while A was down: `b8e785f4` HTTP 503 totalMs 3, both candidates still `ELIGIBLE` (identity cache). Hang `16989931` HTTP 503 totalMs 30007. Mismatch `d95028c9` HTTP 502 totalMs 21, one warn and one routing line. |
| In-flight after success, 503, timeout, 4xx, 502 | PASS | next request's `candidates[].inFlight`, plus tracking rows | After each of those, a later snapshot read 0. SQL `negative_inflight_rows = 0` across 230 rows. During the hang, probe `593776e5` read `8002 inFlight=1` and chose `8001`. After recovery, `3f3a9314` read 0/0. Probe after the 502 (`73c558a5`) read 0/0. |
| Unexpected runtime exception | PASS as unit only | `controllerReleasesReservationWhenTrackingThrowsUnexpectedly` | Not triggered on the live server. The H2 test is the evidence. |
| Client disconnect, worker answers in 8 s | PASS | delay listener on 8002; `curl -m 0.5` | `CURL_RC 28 elapsed 0.508`. Held row `c22a96f1-c0f9-40ec-9b41-dae2eb8fd8d4` HTTP 200 totalMs 8006, received 19:11:06.910109Z, completed 19:11:14.916964Z. Probes from +0.533 s through +7.714 s read `8002 inFlight=1`. Next reservation `90dc0e37` at 19:11:15.018758Z (102 ms after completion) read 0. The slot freed when the worker answered. |
| Client disconnect, worker never answers | PASS | `curl -m 0.4` against a hang socket. Transcript `logs/disconnect-hang.txt` | `CURL_RC 28 elapsed 0.406`. Early probe at 1.632 s (`91f3f0e9`) read `inFlight` 1. Aborted row `6e5eaf89-23b8-465e-8923-ec7cd7f55856` FAILED 503 totalMs 30004, received 19:11:40.103961Z, completed 19:12:10.109777Z. The next reservation `303d53c9` at 19:12:12.101897Z read `inFlight` 0 and was then itself sent to the hang (totalMs 30004). Release matches the 30 s read timeout. |
| Decision fields, both policies | PASS | response, `GET /api/requests/{id}`, log | `strategy` stays `ROUND_ROBIN_ALL`, `VERSION_AWARE`, or `NOT_ROUTED`. `policy` is present. `inFlight` is the pre-reservation snapshot. Sample `logs/api-sample.json`: worker-a, `VERSION_AWARE`, `LEAST_IN_FLIGHT`, chosen `localhost:8001`, totalLatencyMs 29, stored routing equals the response. RR e2e 38/38 also required stored equals response. Error HTTP bodies omit `routing`; GET and the log include it. |
| RR vs LIF, one slow real worker | PASS | `compare.py`, 10 requests, 0.25 s stagger. Worker B is real DistilBERT on 8012 behind a 2.0 s POST delay on 8002. | See the comparison table. Burst walls 4.288 s (RR) and 4.286 s (LIF). No gain is claimed beyond those numbers. |
| Dead worker failure share (check 9) | PASS | `dead_share.py`. Worker B connection-refused. Worker A real. 20 requests. | See the failure-share table. Every failure is HTTP 503 `WORKER_UNAVAILABLE` chosen `localhost:8002`. Every success is HTTP 200 chosen `localhost:8001`. |
| Registry 400/404/409 (H2) | PASS | `RegistryApiTest` | 14/14. |
| Registry 400/404/409 (PostgreSQL) | PASS | live API on `s3_b75e0ad`, `logs/registry-replay-client.txt` | Bad id 400 `9b0235f5-7e5a-43c6-bb05-a54e94ae11cc`. GET missing 404 `a17e00cf-b8b0-414d-8b4c-f95f80e1989c`. List 200. Create `s3-reg-b75` 201 at 2026-10-15T19:15:20.161635Z. Duplicate model 409 `038e1c2f-7a7f-484e-a049-0fd9cb42c4b7`. Bad revision 400 `48d775b6-a490-4a24-90db-12458086c3fc`. Version 201, duplicate version 409 `59b1ce73-9408-4a98-8f81-c326d4789e75`. GET version list 200. |
| Request tracking | PASS | H2 suite and live rows | H2 8/8. Live `inference_requests` has 230 rows. Flyway V1–V3 once. |
| Frontend build and UI | PASS | `npm ci`, `npm run build`, Playwright with `VITE_API_BASE_URL=http://localhost:8080` | `NPM_CI_RC=0`. `BUILD_RC=0`. `screenshots/ui-inference.txt`: Backend connected, "This movie is wonderful.", POSITIVE, worker-a, Eligible 2 of 2, reason "no model requested: least-in-flight over all 2 worker(s)", latency 63 ms. The page does not render `policy` or `inFlight`. |
| Docker | BLOCKED | `docker --version` | `docker: command not found` (exit 127). |

A first `./mvnw -B test` in this shell failed 5 tests (`InferenceApiTest` 4, `StalledWorkerTest` 1) because the shell already had `PLATFORM_WORKER_URLS=http://localhost:8001`, so those tests called that address instead of their fake worker. That log is `logs/mvn-test-inherited-env.log`. It is not the acceptance result. The clean run above is.

## RR vs LIF, one slow real worker

Worker A on port 8001. Worker B is the same DistilBERT revision on port 8012, reached through a 2.0 s delay on POST `/predict` at port 8002. Ten unversioned requests, started 0.25 s apart. `strategy` is `ROUND_ROBIN_ALL` on every row. `stored_eq` is true and the log `chosen` matches. `inFlight` is the snapshot before that request's reservation.

| i | RR endpoint | RR totalMs | RR inFlight 8001 | RR inFlight 8002 | RR wall s | LIF endpoint | LIF totalMs | LIF inFlight 8001 | LIF inFlight 8002 | LIF wall s |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 0 | 8001 worker-a | 74 | 0 | 0 | 0.171 | 8002 worker-b | 2035 | 0 | 0 | 2.055 |
| 1 | 8002 worker-b | 2024 | 0 | 0 | 2.030 | 8001 worker-a | 18 | 0 | 1 | 0.023 |
| 2 | 8001 worker-a | 19 | 0 | 1 | 0.025 | 8001 worker-a | 17 | 0 | 1 | 0.023 |
| 3 | 8002 worker-b | 2018 | 0 | 1 | 2.025 | 8001 worker-a | 15 | 0 | 1 | 0.021 |
| 4 | 8001 worker-a | 17 | 0 | 2 | 0.022 | 8001 worker-a | 15 | 0 | 1 | 0.020 |
| 5 | 8002 worker-b | 2019 | 0 | 2 | 2.026 | 8001 worker-a | 14 | 0 | 1 | 0.019 |
| 6 | 8001 worker-a | 16 | 0 | 3 | 0.028 | 8001 worker-a | 15 | 0 | 1 | 0.020 |
| 7 | 8002 worker-b | 2022 | 0 | 3 | 2.030 | 8001 worker-a | 17 | 0 | 1 | 0.022 |
| 8 | 8001 worker-a | 18 | 0 | 4 | 0.024 | 8001 worker-a | 15 | 0 | 1 | 0.019 |
| 9 | 8002 worker-b | 2023 | 0 | 4 | 2.030 | 8002 worker-b | 2021 | 0 | 0 | 2.028 |

| | ROUND_ROBIN | LEAST_IN_FLIGHT |
| --- | --- | --- |
| burst wall | 4.288 s | 4.286 s |
| requests A / B | 5 / 5 | 8 / 2 |
| totalMs A | 74, 19, 17, 16, 18 | 18, 17, 15, 15, 14, 15, 17, 15 |
| totalMs B | 2024, 2018, 2019, 2022, 2023 | 2035, 2021 |

Under RR, `8002` in-flight climbs 0,0,1,1,2,2,3,3,4,4 and the chooser still alternates. Under LIF, requests 1–8 saw `8002 inFlight=1` and stayed on 8001. Request 9 started after the first slow call had finished, read `inFlight` 0, and took 8002 again. These ten requests are the whole measurement.

## Check 9 — stopped worker, failure share

Worker A real on 8001. Worker B's port 8002 had nothing listening (connection refused, fail fast). Twenty unversioned requests. Counts are successes on 8001 / 503s on 8002.

| load | RR 200 / 503 | LIF 200 / 503 |
| --- | --- | --- |
| 20 started together (barrier) | 10 / 10 | 10 / 10 |
| 20 started 5 ms apart | 10 / 10 | 8 / 12 |
| 20 started 40 ms apart | 10 / 10 | 10 / 10 |

At 40 ms apart every snapshot read `inFlight` 0, and both policies alternated, so each sent 10 of 20 to the closed port. At 5 ms apart the live call was still in progress for some later reservations. RR still sent 10. LIF sent 12 to the closed port and 8 to the live worker. The simultaneous barrier reserved all 20 before the fast failures returned, and both policies split 10 / 10. Nothing beyond these three pairs of 20-request runs is claimed.

## Bugs

No product defect was filed from this SHA. `RoutingDecisionApiTest` matches the additive `policy` and `inFlight` fields, and the clean H2 run is 92/92.

`docs/SPRINT3_ROUTING.md` at this SHA still marks the evidence table `_pending_`. The same doc states that a worker which fails quickly can attract more concurrent LIF traffic than round-robin. The 5 ms row above is that measurement: 12 of 20 LIF requests failed, against 10 of 20 under RR.

The UI shows the reason string, worker, eligible count, and latency. It does not show `policy` or `candidates[].inFlight`. Check 6 was met on the API body, the stored JSON, and the log.

A stopped worker that was previously healthy stays `ELIGIBLE` until the identity cache expires. Unversioned LIF still selects it and returns 503 with no retry. `IDENTITY_UNKNOWN` was only the never-known port 8003.

## Environment

- Clone `/tmp/s3-b75e0ad`, HEAD `b75e0ade91aacdcfb15312e2850e82c86bdf4a8d`. Parent `f07584ac8d1cb2034aebc63bf534797864d499f5`. `60644a1` is an ancestor and was not run.
- Ubuntu 24.04.5 LTS, kernel 6.12.94+.
- OpenJDK 21.0.12.1, Node v22.14.0, npm 10.9.9, Python 3.12.3.
- PostgreSQL 16.15. Database and role `s3_b75e0ad`, created empty (0 public tables), Flyway V1–V3, left in place. JDBC URLs in the Spring logs have no password.
- Workers: DistilBERT `distilbert-base-uncased-finetuned-sst-2-english` revision `714eb0fa89d2f80546fda750413ed43d93601a13`, venv `/tmp/worker-venv` (transformers 5.17.0). The 2 s and 8 s delays are harness delays. The dead-worker port was closed.
- `docker` is not installed. Chrome via Playwright, display `:1`.
- Connect timeout 2000 ms, read timeout 30000 ms. `platform.routing.policy` is unset in `application.properties`. Default when `PLATFORM_ROUTING_POLICY` is unset is `ROUND_ROBIN`.

## Evidence

Logs and screenshots are under `evidence/sprint3-b75e0ad/` and `/cursor/stores/self/artifacts/s3-b75e0ad/`. A secret scan of this tree before commit found no database password, no GitHub token, no cloud access-key prefix, and no private-key block.
