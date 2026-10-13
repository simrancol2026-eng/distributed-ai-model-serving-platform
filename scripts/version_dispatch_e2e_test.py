#!/usr/bin/env python3
"""Sprint 2 end-to-end check of version-aware dispatch on the REAL stack.

Needs: PostgreSQL registry, the backend with PLATFORM_WORKER_URLS, and the two real workers
(see README "Two workers" and "Version-aware dispatch"). Standard library only.

What it does:
  1. Reads each worker's own /health: the artifact and the revision it actually loaded.
  2. Registers a NEW model "vd-check-<time>" with
       v1 = that artifact at the revision the workers really run, and
       v2 = the same artifact at a random 40-hex revision that NO worker runs (synthetic, registered
            later, so it is "latest").
     Nothing is deleted; the rows stay in the registry as test records.
  3. Checks:
       - model + v1: 200, strict A/B alternation, response says modelVersion v1 and the real revision,
         and each worker's predictionsServed counter rose by exactly half the requests;
       - model alone (latest = v2): 422 NO_ELIGIBLE_WORKER and NO worker ran (no substitution of v1);
       - unknown version / unknown model: 404; "version" without "model": 400;
         capability IMAGE for a TEXT model: 422 CAPABILITY_MISMATCH;
       - a request without "model" still works as before (200).

Usage:
  python scripts/version_dispatch_e2e_test.py [--backend http://localhost:8080]
      [--workers http://localhost:8001,http://localhost:8002] [--requests 4]
"""

import argparse
import json
import secrets
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone

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


def served(workers: list[str]) -> list[int]:
    return [call("GET", w + "/health")[1].get("predictionsServed", -1) for w in workers]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--backend", default="http://localhost:8080")
    ap.add_argument("--workers", default="http://localhost:8001,http://localhost:8002")
    ap.add_argument("--requests", type=int, default=4)
    args = ap.parse_args()
    api = args.backend.rstrip("/")
    workers = [w.strip().rstrip("/") for w in args.workers.split(",")]
    n = args.requests

    health = []
    for w in workers:
        status, body = call("GET", w + "/health")
        if not check(status == 200 and isinstance(body, dict) and body.get("revision"),
                     f"{w} /health reports model and revision"):
            print(f"      {status} {body}")
            return 1
        health.append(body)
    artifact, revision = health[0]["model"], health[0]["revision"]
    if not check(all(h["model"] == artifact and h["revision"] == revision for h in health),
                 f"all workers run {artifact} at {revision}"):
        print("      This script expects identical workers; see README for a real second revision.")
        return 1

    model_id = "vd-check-" + datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S") + "-" + secrets.token_hex(2)
    synthetic = secrets.token_hex(20)
    print(f"registering {model_id}: v1={artifact}@{revision} (real), v2=@{synthetic} (synthetic, no worker)")
    check(call("POST", api + "/api/models", {"id": model_id, "name": "Version dispatch check",
                                              "task": "sentiment-analysis", "capability": "TEXT"})[0] == 201,
          "register model")
    check(call("POST", api + f"/api/models/{model_id}/versions",
               {"version": "v1", "artifact": artifact, "artifactRevision": revision})[0] == 201, "register v1")
    check(call("POST", api + f"/api/models/{model_id}/versions",
               {"version": "v2", "artifact": artifact, "artifactRevision": synthetic})[0] == 201,
          "register v2 (synthetic revision)")

    infer = api + "/api/inference"
    before = served(workers)
    order = []
    for i in range(n):
        status, body = call("POST", infer, {"capability": "TEXT", "input": f"v1 request {i}: great",
                                            "model": model_id, "version": "v1"})
        ok = status == 200 and isinstance(body, dict) and body.get("modelVersion") == "v1" \
            and body.get("version") == revision
        check(ok, f"v1 request {i + 1}: {status} worker={body.get('worker') if isinstance(body, dict) else body}")
        if ok:
            order.append(body["worker"])
    check(len(order) == n and all(order[i] != order[i + 1] for i in range(n - 1)), f"alternation {order}")
    after = served(workers)
    check(all(a - b == n // len(workers) for a, b in zip(after, before)),
          f"each worker's own counter rose by {n // len(workers)}: {before} -> {after}")

    before = served(workers)
    status, body = call("POST", infer, {"input": "latest please", "model": model_id})
    check(status == 422 and isinstance(body, dict) and body.get("error") == "NO_ELIGIBLE_WORKER",
          f"latest (= v2, no worker) -> 422 NO_ELIGIBLE_WORKER ({status})")
    check(served(workers) == before, "no worker ran for the unservable version (no substitution)")

    for payload, want, code in [
        ({"input": "x", "model": model_id, "version": "v9"}, 404, "VERSION_NOT_FOUND"),
        ({"input": "x", "model": model_id + "-missing"}, 404, "MODEL_NOT_FOUND"),
        ({"input": "x", "version": "v1"}, 400, "INVALID_REQUEST"),
        ({"capability": "IMAGE", "input": "x", "model": model_id, "version": "v1"}, 422, "CAPABILITY_MISMATCH"),
    ]:
        status, body = call("POST", infer, payload)
        check(status == want and isinstance(body, dict) and body.get("error") == code,
              f"{json.dumps(payload)} -> {want} {code} ({status})")

    status, body = call("POST", infer, {"capability": "TEXT", "input": "no model named"})
    check(status == 200 and isinstance(body, dict) and body.get("modelId") is None,
          f"request without model still 200 ({status})")

    failed = [label for ok, label in results if not ok]
    print(f"\n{len(results) - len(failed)}/{len(results)} checks passed (test model {model_id} left in registry)")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
