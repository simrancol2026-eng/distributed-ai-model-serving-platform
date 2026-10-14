#!/usr/bin/env python3
"""Sprint 2 #5 end-to-end check of routing decisions on the REAL stack.

Needs: PostgreSQL, the backend, and real workers (see README "Routing decisions"). Standard library only.
Nothing is deleted.

Checks:
  1. requests that name no model -> 200 with routing.strategy ROUND_ROBIN_ALL, every configured worker
     listed as ANY_WORKER, routing.chosen == workerEndpoint, and GET /api/requests/{id} returns the SAME
     routing object; with >= 2 workers, consecutive requests rotate (round-robin unchanged);
  2. blank input -> 400, stored routing NOT_ROUTED / INVALID_REQUEST with no candidates and no choice;
  3. unknown model -> 404, stored routing NOT_ROUTED / MODEL_NOT_FOUND;
  4. with --model (and optional --version): 200 with routing.strategy VERSION_AWARE, the chosen worker is
     a candidate marked ELIGIBLE, eligibleCount matches the ELIGIBLE candidates, stored == response;
  5. with --log FILE (the backend's log): each request above has exactly one
     "routing decision requestId=<id>" line, and its chosen=... matches the stored decision.

Usage:
  python scripts/routing_decision_e2e_test.py [--backend http://localhost:8080] [--model ID [--version V]]
      [--log backend.log]
"""

import argparse
import json
import re
import sys
import urllib.error
import urllib.request

results: list[tuple[bool, str]] = []


def check(ok: bool, label: str) -> bool:
    results.append((ok, label))
    print(("PASS " if ok else "FAIL ") + label, flush=True)
    return ok


def call(method: str, url: str, body: dict | None = None) -> tuple[int | None, dict | list | str]:
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


def stored(base: str, request_id: str) -> dict | None:
    status, body = call("GET", f"{base}/api/requests/{request_id}")
    return body.get("routing") if status == 200 and isinstance(body, dict) else None


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--backend", default="http://localhost:8080")
    ap.add_argument("--model")
    ap.add_argument("--version")
    ap.add_argument("--log", help="backend log file to cross-check the 'routing decision' lines")
    args = ap.parse_args()
    base = args.backend.rstrip("/")
    seen: dict[str, dict] = {}  # requestId -> stored routing

    # 1. no model: round-robin over all workers
    chosen = []
    for i in range(4):
        status, body = call("POST", f"{base}/api/inference", {"input": f"routing check {i}"})
        if not check(status == 200 and isinstance(body, dict), f"no-model request {i} -> 200 ({status})"):
            continue
        r = body.get("routing") or {}
        rid = body["requestId"]
        check(r.get("strategy") == "ROUND_ROBIN_ALL", f"[{rid[:8]}] strategy ROUND_ROBIN_ALL")
        cands = r.get("candidates") or []
        check(len(cands) == r.get("poolSize") == r.get("eligibleCount") and cands
              and all(c.get("note") == "ANY_WORKER" for c in cands), f"[{rid[:8]}] all {len(cands)} workers ANY_WORKER")
        check(r.get("chosen") == body.get("workerEndpoint"), f"[{rid[:8]}] chosen == workerEndpoint")
        s = stored(base, rid)
        check(s == r, f"[{rid[:8]}] stored routing == response routing")
        seen[rid] = s or {}
        chosen.append(r.get("chosen"))
    pool = len(set(c["endpoint"] for r in seen.values() for c in r.get("candidates", [])))
    if pool >= 2 and len(chosen) == 4:
        check(all(chosen[i] != chosen[i + 1] for i in range(3)), f"round-robin rotates: {chosen}")

    # 2. rejected before routing
    status, body = call("POST", f"{base}/api/inference", {"input": "   "})
    if check(status == 400 and isinstance(body, dict), f"blank input -> 400 ({status})"):
        s = stored(base, body["requestId"]) or {}
        check(s.get("strategy") == "NOT_ROUTED" and s.get("reason") == "INVALID_REQUEST"
              and s.get("candidates") == [] and s.get("chosen") is None, f"stored NOT_ROUTED/INVALID_REQUEST: {s}")
        seen[body["requestId"]] = s

    # 3. unknown model
    status, body = call("POST", f"{base}/api/inference", {"input": "x", "model": "no-such-model-e2e"})
    if check(status == 404 and isinstance(body, dict), f"unknown model -> 404 ({status})"):
        s = stored(base, body["requestId"]) or {}
        check(s.get("strategy") == "NOT_ROUTED" and s.get("reason") == "MODEL_NOT_FOUND",
              f"stored NOT_ROUTED/MODEL_NOT_FOUND: {s}")
        seen[body["requestId"]] = s

    # 4. version-aware
    if args.model:
        req = {"input": "versioned routing check", "model": args.model}
        if args.version:
            req["version"] = args.version
        status, body = call("POST", f"{base}/api/inference", req)
        if check(status == 200 and isinstance(body, dict), f"model {args.model} -> 200 ({status}) {body}"):
            r = body.get("routing") or {}
            cands = r.get("candidates") or []
            eligible = [c for c in cands if c.get("note") == "ELIGIBLE"]
            check(r.get("strategy") == "VERSION_AWARE", "strategy VERSION_AWARE")
            check(r.get("eligibleCount") == len(eligible) > 0, f"eligibleCount {r.get('eligibleCount')} == ELIGIBLE candidates")
            check(r.get("chosen") in [c["endpoint"] for c in eligible], f"chosen {r.get('chosen')} is ELIGIBLE")
            check(all(c.get("served") == r.get("target") for c in eligible), "every ELIGIBLE candidate serves the target")
            s = stored(base, body["requestId"])
            check(s == r, "stored routing == response routing")
            seen[body["requestId"]] = s or {}

    # 5. log lines
    if args.log:
        with open(args.log, encoding="utf-8", errors="replace") as f:
            text = f.read()
        for rid, s in seen.items():
            lines = re.findall(rf"routing decision requestId={rid} .*", text)
            want = s.get("chosen") or "-"
            check(len(lines) == 1 and f" chosen={want} " in lines[0],
                  f"[{rid[:8]}] one log line, chosen={want}")

    failed = [label for ok, label in results if not ok]
    print(f"\n{len(results) - len(failed)}/{len(results)} checks passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
