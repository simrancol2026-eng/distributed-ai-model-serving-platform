# Sprint 3: intelligent routing, first slice (least-in-flight)

Status: **DESIGN AGREED, IMPLEMENTATION IN PROGRESS (Codex). Nothing in this document has been tested yet.**
Base: `main` at `1c1d932` (Sprint 2 routing decisions merged; tree `31795af`).
Owners: Codex implements, Claude reviews the design and code and maintains this document, Grok tests independently on an exact SHA.

Every result in this document is marked _pending_ until a Grok TEST REPORT on an exact commit exists. When one exists, this file records the SHA and the evidence location. No result here is a prediction.

---

## 1. What this slice adds

An **optional** routing policy called *least-in-flight* (LIF). When the backend picks a worker for a request, it chooses the eligible worker that currently has the **fewest requests in progress from this backend**.

- **Round-robin stays the default** and is the comparison baseline. LIF is switched on only by configuration.
- **Eligibility does not change.** LIF chooses only among the workers the Sprint 2 rules already allow:
  - no `model` in the request: any configured worker;
  - `model`/`version` given: only workers whose `/health` reports exactly the resolved artifact and revision.
  LIF never widens that list, and it never substitutes another model version.
- **Error behaviour does not change**: 400/404/422/503/502 work exactly as before. There is still **no automatic retry or failover**. A chosen worker that is down still returns 503.

### What it is not

- It is **not** health-aware or latency-aware routing. Those are later Sprint 3 slices, each reviewed separately.
- It is **not** an AI or learned routing algorithm. It is a simple, well-known load-balancing rule, and it is the first step towards the project's research question.
- It does **not** know the whole cluster's load. The counts are kept **inside one backend process (JVM)**. Requests sent to the workers by anything else, such as a second backend instance or a direct `curl` to a worker, are invisible to it.
- **No performance improvement is claimed.** Whether LIF helps is measured in section 5. Correctness also remains pending until the full regression run succeeds.

---

## 2. Configuration

| Property | Values | Default | Notes |
|---|---|---|---|
| `platform.routing.policy` | `ROUND_ROBIN`, `LEAST_IN_FLIGHT` | `ROUND_ROBIN` | An unknown value stops startup with a clear error (fail fast). Set once at startup; there is no per-request override in this slice. |

Set `PLATFORM_ROUTING_POLICY=LEAST_IN_FLIGHT` before starting the backend. Values are case-sensitive.

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

The decision is built from the same snapshot that made the choice. There are no extra identity probes, and no counter is advanced twice just to produce a log line.

---

## 5. Evidence plan (owner: Grok; all results pending)

| # | Check | How | Result |
|---|---|---|---|
| a | Default is still round-robin; Sprint 2 e2e scripts (round_robin, version_dispatch, routing_decision, phase1) still pass | fresh clone, real workers, native PostgreSQL | _pending_ |
| b | LIF never chooses a SERVES_OTHER or IDENTITY_UNKNOWN worker | version_dispatch scenario with LIF on | _pending_ |
| c | In-flight returns to 0 after every outcome (success, worker down, timeout, rejection before dispatch, client disconnect) and never goes negative | unit tests plus a real-worker run | _pending_ |
| d | Sequential requests under LIF still alternate A/B | 6 sequential requests | _pending_ |
| e | Under concurrent load with one deliberately slow worker: RR vs LIF request counts per worker | concurrent driver; counts taken from decision logs and tracking rows | _pending_ |
| f | Stored decision names the policy and the in-flight counts it used | `GET /api/requests/{id}` matches the response and the log line | _pending_ |
| g | Regressions: registry, request tracking, frontend | existing suites | _pending_ |

Unit and H2 tests are reported separately from real-worker, native-PostgreSQL runs. Docker remains **WORK IN PROGRESS / unverified** and is not part of this evidence.

**Expected behaviour, not a result:** LIF can only differ from round-robin when requests overlap. With strictly sequential traffic, both policies produce the same A/B alternation. That's why check (e) needs a concurrent driver and an uneven worker.

---

## 6. Demo outline (to finalize after the build passes)

1. Start the normal stack (see `docs/DEMO.md` on PR #5), with `platform.routing.policy` left at the default, and show that the routing panel and `GET /api/requests/{id}` report `policy = ROUND_ROBIN`.
2. Restart the backend only with `PLATFORM_ROUTING_POLICY=LEAST_IN_FLIGHT`. Send a few sequential requests and show that they still alternate, explaining the tie rotation.
3. Show a stored decision with its per-candidate `inFlight` values.
4. Present Grok's measured concurrent comparison from check (e) as recorded evidence, not as a live benchmark, and only if it exists.
5. State the limits: counts are local to one backend, there is no failover, and health/latency awareness comes later.

---

## 7. Open questions

- Should the frontend show `policy` and `inFlight`? It is small, but it touches `frontend/` (read `frontend/AGENTS.md` first) and adds tests. Proposed: yes, in the same slice only if it stays small, otherwise as a follow-up.
- Should there be a read-only endpoint for current in-flight counts (useful for the demo and Sprint 5 monitoring)? Proposed: not in this slice.


