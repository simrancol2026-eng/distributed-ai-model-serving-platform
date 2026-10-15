# Sprint 3: intelligent routing (least-in-flight and health/latency)

Status: **two opt-in policies merged; Sprint 3 is still in progress (not a sprint closeout). Docker remains WORK IN PROGRESS / unverified.**

| Slice | Policy | PR | Tested head | Merge on `main` | Evidence |
|---|---|---|---|---|---|
| 1 | `LEAST_IN_FLIGHT` (sections 1–6) | #6 | `b75e0ad` | `a54aba5` | `evidence-b75e0ad` @ `59fd796` |
| 2 | `HEALTH_LATENCY` (section 7) | #7 | `ed28851` | `4a2c343` | `evidence-8a27cea` @ `d33b1e9` (first run, FAIL), `evidence-ed28851` @ `9f36761` (retest, PASS) |

Round-robin (`ROUND_ROBIN`) is still the default. Each merge is a normal (non-squash) merge whose runtime code equals the tested head.
Owners: Codex implements, Claude reviews the design and code and maintains this document, Grok tests independently on an exact SHA.

Sections 1–6 describe slice 1. Its tested commit is `b75e0ade91aacdcfb15312e2850e82c86bdf4a8d`; evidence commit `59fd7968dd39dfca178cebd144afe78aa366d9f5` (parent `b75e0ad`, adds only `evidence/sprint3-b75e0ad/`). Results come from Grok's reports and their raw logs; nothing here is a prediction.

---

## 1. What this slice adds

An **optional** routing policy called *least-in-flight* (LIF). When the backend picks a worker for a request, it chooses the eligible worker that currently has the **fewest requests in progress from this backend**.

- **Round-robin stays the default** and is the comparison baseline. LIF is switched on only by configuration.
- **Eligibility does not change.** LIF chooses only among the workers the Sprint 2 rules already allow:
  - no `model` in the request: any configured worker;
  - `model`/`version` given: only workers whose `/health` reports exactly the resolved artifact and revision.
  LIF never widens that list, and it never substitutes another model version.
- **Error behaviour does not change**: 400/404/422/503/502 work exactly as before. There is still **no automatic retry or failover**. A chosen worker that is down still returns 503. A worker that fails quickly often has zero in-flight requests and can attract more concurrent LIF traffic than round-robin, increasing the failure share. Health-aware routing is required to address this; Grok must measure the stopped-worker case.

### What it is not

- It is **not** health-aware or latency-aware routing. That is slice 2, `HEALTH_LATENCY` (section 7).
- It is **not** an AI or learned routing algorithm. It is a simple, well-known load-balancing rule, and it is the first step towards the project's research question.
- It does **not** know the whole cluster's load. The counts are kept **inside one backend process (JVM)**. Requests sent to the workers by anything else, such as a second backend instance or a direct `curl` to a worker, are invisible to it.
- **No performance improvement is claimed.** What LIF does under load is measured in section 5.

---

## 2. Configuration

| Property | Values | Default | Notes |
|---|---|---|---|
| `platform.routing.policy` | `ROUND_ROBIN`, `LEAST_IN_FLIGHT`, `HEALTH_LATENCY` | `ROUND_ROBIN` | An unknown value stops startup with a clear error (fail fast). Set once at startup; there is no per-request override. |

Set `PLATFORM_ROUTING_POLICY=LEAST_IN_FLIGHT` (or `HEALTH_LATENCY`) before starting the backend. Values are case-sensitive.

---

## 3. How a worker is chosen

For each request that reaches routing:

1. Build the eligible list exactly as in Sprint 2. Identity probes and registry reads happen here, **outside** any lock.
2. In **one short critical section** with no network or database work inside it:
   1. read every eligible worker's in-flight count (this snapshot is what gets logged);
   2. choose the worker with the lowest count;
   3. **break ties by rotation**, starting from the next round-robin position for this target, so idle workers still take turns;
   4. add 1 to the chosen worker's count (the *reservation*).
3. Outside the lock: write the DISPATCHED tracking row, call the worker, map the result.
4. **Release** the reservation (subtract 1) in a `finally` block, so it happens on every outcome: success, 503, 502, timeout, model mismatch or an unexpected exception. Releasing twice has no effect, so the count can never go negative.

### Why each rule exists

- **One critical section.** Without it, two simultaneous requests can both see "A=0, B=0" and both choose A. Reading, choosing and reserving together prevents that. The section only compares a few integers, so holding it is cheap.
- **Tie rotation.** Requests sent one after another, as in a live demo, always find both workers idle (0 and 0). If ties always went to the first worker, every demo request would land on worker-a. Rotating keeps sequential traffic alternating A, B, A, B, the same as round-robin.
- **Release in `finally`.** A count that never comes back down would make a worker look permanently busy and starve it.
- **What the count includes.** The slot is held from selection until the worker call ends. That covers the bounded DISPATCHED tracking write (connection acquisition plus statement timeout, each configured to 2 s by default) and the worker call itself (up to the 30 s read timeout).
- **Client disconnects.** Spring MVC does not interrupt the worker call when the browser disconnects, so the slot is freed when the worker answers or the read timeout fires, not instantly.
- **Both policies count.** In-flight counts are kept under round-robin as well, so the baseline logs comparable numbers.

---

## 4. What the routing decision records

The Sprint 2 decision (logged, stored with the request, returned in successful responses) stays the same. Existing fields keep their meaning, and `strategy` is still the eligibility mode: `ROUND_ROBIN_ALL`, `VERSION_AWARE` or `NOT_ROUTED`.

Decision additions:

| Field | Meaning |
|---|---|
| `policy` | `ROUND_ROBIN` or `LEAST_IN_FLIGHT`: which rule chose among the eligible workers |
| `candidates[].inFlight` | that worker's in-flight count read inside the selection lock, **before** this request's own reservation |
| `reason` | names the policy, e.g. `least-in-flight over 2 eligible (tie broken by rotation)` |

For selected workers, the decision is built from the same snapshot that made the choice. If no worker is eligible, counts are a diagnostic snapshot taken after eligibility checks; no selection or reservation occurred. There are no extra identity probes, and no counter is advanced twice just to produce a log line.

---

## 5. Evidence (Grok, exact SHA `b75e0ad`)

| # | Check | How | Result |
|---|---|---|---|
| a | Default is still round-robin; Sprint 2 e2e scripts (round_robin, version_dispatch, routing_decision, phase1) still pass | fresh clone, real workers, native PostgreSQL | PASS: default `ROUND_ROBIN`; phase1 8/8, round_robin 23/23, version_dispatch 19/19, routing_decision 38/38. Unknown (`NOT_A_POLICY`) and wrong-case (`least_in_flight`) values exit 1 at startup |
| b | LIF never chooses a SERVES_OTHER or IDENTITY_UNKNOWN worker | version_dispatch scenario with LIF on | PASS: ineligible workers never chosen; an unserved version returns 422 with `chosen: null` |
| c | In-flight returns to 0 after every outcome (success, worker down, timeout, rejection before dispatch, client disconnect) and never goes negative | unit tests plus a real-worker run | PASS: back to 0 after 400/404/422/502/503 and timeout; 0 negative values in 230 stored rows. Client disconnect: slot freed when the worker answered (8.0 s) or at the 30 s read timeout on a hang |
| d | Sequential requests under LIF still alternate A/B | 6 sequential requests | PASS: alternated 8001/8002, unversioned and versioned |
| e | Under concurrent load with one deliberately slow worker: RR vs LIF request counts per worker | concurrent driver; counts taken from decision logs and tracking rows | MEASURED (see below): worker B delayed 2.0 s, 10 requests 0.25 s apart. RR 5/5, LIF 8/2 (A/B); wall time 4.288 s vs 4.286 s |
| f | Stored decision names the policy and the in-flight counts it used | `GET /api/requests/{id}` matches the response and the log line | PASS under both policies |
| g | Regressions: registry, request tracking, frontend | existing suites | PASS: Maven 92/92 (H2/fake workers); registry 400/404/409 live and on H2; frontend build and UI |

Unit and H2 tests are reported separately from real-worker, native-PostgreSQL runs. Docker remains **WORK IN PROGRESS / unverified** and is not part of this evidence.

LIF can only differ from round-robin when requests overlap. With strictly sequential traffic both policies alternate A/B, which check (d) confirmed.

### What the measurements show, and what they don't

- **Slow worker (check e).** LIF sent fewer requests to the slow worker (2 of 10, against 5 of 10 for RR). Total wall time was the same (about 4.29 s for both), because in this test the last slow request finishes at about the same time either way. **No speed-up is claimed.** It was one run of 10 requests with one artificial delay, so it shows the behaviour, not a benchmark.
- **Stopped worker (dead-worker caveat, section 1).** Port 8002 was closed and 20 requests were sent; the numbers are successes/503s.

| Arrival pattern | RR (200/503) | LIF (200/503) |
|---|---|---|
| All started together | 10/10 | 10/10 |
| 5 ms apart | 10/10 | 8/12 |
| 40 ms apart | 10/10 | 10/10 |

  At 5 ms spacing LIF sent *more* requests to the dead worker than round-robin did, because a fast-failing worker never looks busy. That confirms the caveat; health-aware routing is needed to fix it. These are single runs, so the exact split will vary.
- **Stale identity.** A worker that was healthy and is then stopped can stay ELIGIBLE indefinitely. When the identity cache is older than its maximum age the backend re-probes `/health`, but if that probe fails it *keeps the last known identity* (`WorkerClient.servedModel`). Cache expiry therefore does not remove a dead worker; only a successful probe that reports something else, or a model-mismatch reply, changes it. Eligibility checks identity, not live health. This is a known limitation, left for the health-aware routing work.

Raw logs: `evidence/sprint3-b75e0ad/logs/compare-rr.txt`, `compare-lif.txt` and `dead-*.txt` on commit `59fd796`.

---

## 6. Demo outline

1. Start the normal stack (see `docs/DEMO.md`), with `platform.routing.policy` left at the default, and show that the inference API response and `GET /api/requests/{id}` report `policy = ROUND_ROBIN`.
2. Restart the backend only with `PLATFORM_ROUTING_POLICY=LEAST_IN_FLIGHT`. Send a few sequential requests and show that they still alternate, explaining the tie rotation.
3. Show a stored decision with its per-candidate `inFlight` values.
4. Present Grok's recorded comparison from section 5 (slow worker and stopped worker) as evidence, not as a live benchmark. Say plainly that LIF moved load off the slow worker but did not reduce total time, and that it can make a dead worker worse.
5. State the limits: counts are local to one backend and there is no failover. For a dead or slow worker, continue with the `HEALTH_LATENCY` demo in section 7.6.

---

## 7. Slice 2: `HEALTH_LATENCY`

Tested head `ed288514db9a29dd03410d7bce17f7edcfae6768` (PR #7), merged as `4a2c343`.

### 7.1 What it adds

An opt-in policy that **skips workers whose health checks are failing** and, among the rest, **prefers the worker expected to finish soonest**:

`score = (inFlight + 1) × estimated latency`, and the lowest score wins.

- Round-robin and least-in-flight behave exactly as before. Their tests and e2e scripts are unchanged.
- Eligibility is still exact: the same model artifact and revision, and the same capability. Nothing is substituted.
- Still **no retry and no failover**. A request sent to a worker that dies mid-call still fails.
- Health, latency and counts are kept **inside one backend process**.

### 7.2 Health checks

- A **background poller** calls each worker's `GET /health` (fixed 2 s delay after each check ends, at most 4 probe threads). Requests never probe, so no request waits on a slow `/health`.
- A worker is **live** when its last successful check is at most **10 s old** and it has not failed **2 checks in a row**. At default settings, 10 s = 2 × (2 s interval + 2 s probe timeout) + 2 s. This accommodates two timed-out probes in the tested schedule. Expiry can still exclude a worker if polling is delayed, including contention for the bounded probe threads.
- **One successful check readmits** a worker.
- A worker that has **never** been checked is not live. The first checks run as the backend starts, so in practice this window is very short. Grok saw a 503 at 0.085 s only when the first check was artificially held.
- Liveness, capability and identity (artifact@revision) all come from the **same** `/health` reply.
- A reply naming the wrong model (`WORKER_MODEL_MISMATCH`, 502) clears that worker's identity, so it is skipped until its next successful check.
- `/health` proves that the worker process answers, not that the next inference will succeed.

### 7.3 Latency estimate and cooldown

- **Latency** is the duration of *successful* `/predict` calls, smoothed (EWMA, α = 0.2). Failures and timeouts never improve it.
- A worker with **no sample, or a sample older than 30 s,** is treated as optimistic: its estimate becomes the lowest fresh estimate among live, eligible workers (100 ms if there is none). This lets it compete for traffic, but does not guarantee a request or a bounded exploration time. Its next successful call resets a stale estimate.
- **Cooldown (5 s)** starts after a transport failure (connection refused or timeout), or after **2 consecutive** 5xx replies. A single 5xx or any 4xx does not start one.
- Cooldown is **soft**: if every live, eligible worker is cooling down, the one whose cooldown ends first is used (`COOLDOWN_OVERRIDE`). A failing health check is a **hard** exclusion.

### 7.4 Errors and the routing decision

| Situation | Response |
|---|---|
| Every worker has a fresh, successful check and none serves the requested capability/version | **422 `NO_ELIGIBLE_WORKER`**: "No worker serves the requested capability or model version…" |
| No live worker serves it, or some checks are failing or missing, so absence cannot be proven | **503 `NO_HEALTHY_WORKER`**: "No live worker can serve this request right now…" |

Each candidate in the decision carries a fixed note: `ELIGIBLE`, `UNHEALTHY`, `CAPABILITY_MISMATCH`, `IDENTITY_UNKNOWN`, `SERVES_OTHER`, `COOLDOWN` or `COOLDOWN_OVERRIDE`. It also carries a `health` object:

| Field | Meaning |
|---|---|
| `live` | the liveness rule in 7.2 |
| `healthAgeMs` | age of the last successful check |
| `ewmaMs`, `samples`, `sampleAgeMs` | the latency estimate, how many successful calls it is built from, and how old the newest is |
| `estimateMs`, `score` | values used for this choice (`null` for workers that could not be chosen) |
| `cooldownMs` | remaining cooldown |

The API response, the stored decision and the log line agree. Grok checked this under all three policies.

### 7.5 Evidence

First run on `8a27cea` (evidence `d33b1e9`) **FAILED** on three bugs, each also raised in code review:

- one slow check excluded a worker, because the window was then 5 s;
- an `Error` thrown inside a check stopped all checks for that worker;
- the 422 message was wrong.

All three were fixed in `ed28851`. Retest on `ed28851` (evidence `9f36761`): **PASS**. Environment: real PostgreSQL 16 and real DistilBERT workers; "ART" marks artificial faults or delays.

| Check | Result |
|---|---|
| Maven, H2/fake workers | 116/116 |
| Slow check (ART) | still live at 5.1 s and 7.2 s; excluded at 8.2 s after the 2nd failure |
| Error inside a check (harness) | counted as a failure; checks keep running |
| 2 failures exclude / 1 success readmits | PASS (readmitted in 1.8 s) |
| Cooldown (ART `/predict` 500) | 2 × 5xx → COOLDOWN (~5 s); 1 × 5xx → none |
| In-flight release in tested success and injected failure/hold paths | PASS; 0 negative rows |
| 422 vs 503 messages | distinct and correct |
| RR and LIF e2e under their own policies | unchanged: 23/23, 19/19, 31/31 |

**Measured comparisons** (single runs):

| Scenario | RR | LIF | HEALTH_LATENCY |
|---|---|---|---|
| Worker B never started, 20 requests (200/503), first-run evidence `d33b1e9` on `8a27cea` | 10/10 | 10/10 | 20/0 |
| Worker B killed (SIGTERM) mid-run, 30 requests (200 on A / 200 on B / 503) | 15/1/14 | 15/1/14 | 26/3/1 |

### What these show, and what they don't

- **Dead worker: fewer failures after detection, not zero.** In the mid-run kill, HEALTH_LATENCY had 1 failure against 14. The first request to the dead worker still fails (there is no retry). Cooldown then takes it out of rotation, and the health checks keep it out.
- **A worker that *suddenly* turns slow keeps receiving traffic for a while.** The estimate only changes when calls *complete*, so the only fast signal is the in-flight count. In Grok's warmed test (worker B fast, then an ART 2.0 s delay, 8 concurrent requests), 7 of 8 still went to B. Fixing this, for example by using the age of calls still in flight, would be a later slice.
- **Sequential traffic need not alternate.** With nothing in flight, selection favors the lower estimate. Estimates can change after each success, ties rotate, and samples older than 30 s use the optimistic estimate. This is why the e2e scripts require alternation only under RR/LIF.
- **No speed-up is claimed.** An earlier cold-start slow-worker run split 5/5 with no meaningful difference, because no latency samples existed yet.

### 7.6 Demo outline

1. Restart the backend with `PLATFORM_ROUTING_POLICY=HEALTH_LATENCY`. Send a request and show the `health` evidence and `score` in the response and in `GET /api/requests/{id}`.
2. Send several sequential requests. Point out that they tend to favour the lower-estimate worker, and explain the changing estimates and ties (7.3 and 7.5).
3. Stop one worker. If a request reaches it before health detection, show its failure (503, no retry) and subsequent `COOLDOWN` or `UNHEALTHY` decisions. A visible failure is not guaranteed: background health checks may exclude it first.
4. Restart the worker. Within one successful check it is `ELIGIBLE` again.
5. Present the measured table in 7.5 as recorded evidence, and say plainly: fewer failures after detection, not zero; no speed-up claim; a sudden slowdown is not detected quickly.

---

## 8. Open questions

- Should the frontend show `policy`, `inFlight` and the `health` evidence? It is small, but it touches `frontend/` (read `frontend/AGENTS.md` first) and adds tests. Proposed: yes, in the same slice only if it stays small, otherwise as a follow-up.
- Should there be a read-only endpoint for current in-flight counts (useful for the demo and Sprint 5 monitoring)? Proposed: not in this slice.
