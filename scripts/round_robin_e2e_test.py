#!/usr/bin/env python3
"""Sprint 2 end-to-end check: two REAL worker processes behind the Spring Boot backend.

Prerequisites (each in its own terminal; see README "Two workers"):
  cd model-workers/model-a
  WORKER_ID=worker-a uvicorn worker:app --port 8001
  WORKER_ID=worker-b uvicorn worker:app --port 8002
  cd backend/spring-boot
  PLATFORM_WORKER_URLS=http://localhost:8001,http://localhost:8002 ./mvnw spring-boot:run
(The backend also needs its registry database; see README.)

What it checks (standard library only):
  1. Both workers answer /health as two different processes (different WORKER_ID and pid).
  2. N inference requests through POST /api/inference alternate strictly between the two
     workers, and each response's worker id matches the endpoint the backend chose.
  3. Each worker's own predictionsServed counter went up by exactly N/2, so both workers
     really ran the model. (Send no other traffic to the workers while this runs.)

Usage:
  python scripts/round_robin_e2e_test.py [--backend http://localhost:8080]
      [--workers http://localhost:8001,http://localhost:8002] [--requests 8]
"""

import argparse
import json
import sys
import urllib.error
import urllib.request

results: list[tuple[bool, str]] = []


def check(ok: bool, label: str) -> bool:
    results.append((ok, label))
    print(("PASS " if ok else "FAIL ") + label, flush=True)
    return ok


def call(method: str, url: str, body: dict | None = None) -> tuple[int | None, dict | str]:
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, method=method, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            raw, status = r.read().decode(), r.status
    except urllib.error.HTTPError as e:
        raw, status = e.read().decode(), e.code
    except OSError as e:
        return None, str(e)
    try:
        return status, json.loads(raw)
    except ValueError:
        return status, raw


def endpoint(url: str) -> str:
    host_port = url.split("://", 1)[1].split("/", 1)[0].lower()
    return host_port if ":" in host_port else host_port + (":443" if url.startswith("https") else ":80")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--backend", default="http://localhost:8080")
    ap.add_argument("--workers", default="http://localhost:8001,http://localhost:8002",
                    help="the same list, in the same order, as PLATFORM_WORKER_URLS")
    ap.add_argument("--requests", type=int, default=8)
    args = ap.parse_args()
    workers = [w.strip().rstrip("/") for w in args.workers.split(",")]
    n = args.requests
    if len(workers) != 2 or n < 4 or n % 2:
        print("Use exactly two workers and an even number of requests (>= 4).")
        return 2

    health = {}
    for w in workers:
        status, body = call("GET", w + "/health")
        if not check(status == 200 and isinstance(body, dict), f"worker {w} /health is 200"):
            print(f"      {body}")
            return 1
        health[w] = body
    ids = [health[w].get("worker") for w in workers]
    pids = [health[w].get("pid") for w in workers]
    check(ids[0] != ids[1], f"two different worker ids: {ids}")
    check(None not in pids and pids[0] != pids[1], f"two different processes (pid): {pids}")
    before = {w: health[w].get("predictionsServed") for w in workers}
    id_to_endpoint = {health[w]["worker"]: endpoint(w) for w in workers}

    served = []
    for i in range(n):
        status, body = call("POST", args.backend + "/api/inference",
                            {"capability": "TEXT", "input": f"Request {i}: I really like this."})
        if not check(status == 200 and isinstance(body, dict) and body.get("status") == "success",
                     f"request {i + 1}/{n} -> 200"):
            print(f"      {status} {body}")
            continue
        served.append(body["worker"])
        check(id_to_endpoint.get(body["worker"]) == body.get("workerEndpoint"),
              f"request {i + 1}: worker {body['worker']} answered from {body.get('workerEndpoint')} "
              f"({body['prediction']['label']} {body['prediction']['score']:.4f})")

    print(f"order: {served}")
    alternates = len(served) == n and all(served[i] != served[i + 1] for i in range(n - 1))
    check(alternates, "strict alternation A, B, A, B, ...")

    for w in workers:
        status, body = call("GET", w + "/health")
        delta = body.get("predictionsServed", 0) - before[w] if isinstance(body, dict) else None
        check(delta == n // 2, f"{health[w]['worker']} served {delta} of {n} (expected {n // 2}, from its own counter)")

    failed = [label for ok, label in results if not ok]
    print(f"\n{len(results) - len(failed)}/{len(results)} checks passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
