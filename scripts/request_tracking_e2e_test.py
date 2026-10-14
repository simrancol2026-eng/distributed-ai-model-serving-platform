#!/usr/bin/env python3
"""Sprint 2 end-to-end check of persistent request lifecycle tracking on the REAL stack.

Needs: PostgreSQL registry, the backend, and at least one real worker (see README "Request lifecycle
tracking"). Standard library only. Nothing is deleted; every request it sends stays tracked.

Checks:
  1. a request that names no model -> 200, and GET /api/requests/{id} shows SUCCEEDED with the same
     worker endpoint, worker id, http status 200, receivedAt <= dispatchedAt <= completedAt, the input
     LENGTH but never the input text;
  2. an invalid request (blank input) -> 400, tracked as FAILED/INVALID_REQUEST and never dispatched;
  3. an unknown model -> 404, tracked as FAILED/MODEL_NOT_FOUND with the requested model and version;
  4. GET /api/requests?limit=3 lists the last three requests newest first; bad limits -> 400;
  5. N concurrent requests -> N distinct requestIds, each with exactly one final status that matches
     the HTTP status the client actually received;
  6. unknown requestId -> 404, malformed requestId -> 400.

Persistence across a backend restart (two runs):
  python scripts/request_tracking_e2e_test.py --save /tmp/tracked.json      # run 1, writes ids+statuses
  (stop and start the backend, same database)
  python scripts/request_tracking_e2e_test.py --verify /tmp/tracked.json    # run 2, re-reads every id

Usage:
  python scripts/request_tracking_e2e_test.py [--backend http://localhost:8080] [--concurrent 10]
      [--save FILE | --verify FILE]
"""

import argparse
import json
import sys
import urllib.error
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime

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


def summary() -> int:
    failed = [label for ok, label in results if not ok]
    print(f"\n{len(results) - len(failed)}/{len(results)} checks passed")
    return 1 if failed else 0


def verify(api: str, path: str) -> int:
    with open(path, encoding="utf-8") as f:
        saved = json.load(f)
    print(f"re-reading {len(saved)} tracked requests saved by an earlier run")
    for request_id, expected in saved.items():
        status, t = call("GET", f"{api}/api/requests/{request_id}")
        ok = status == 200 and isinstance(t, dict) and t.get("status") == expected["status"] \
            and t.get("httpStatus") == expected["httpStatus"]
        check(ok, f"{request_id} still {expected['status']}/{expected['httpStatus']} after restart ({status})")
    return summary()


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--backend", default="http://localhost:8080")
    ap.add_argument("--concurrent", type=int, default=10)
    group = ap.add_mutually_exclusive_group()
    group.add_argument("--save", help="write tracked ids and final statuses to this JSON file")
    group.add_argument("--verify", help="re-read ids saved by --save (run after a backend restart)")
    args = ap.parse_args()
    api = args.backend.rstrip("/")
    infer = api + "/api/inference"
    if args.verify:
        return verify(api, args.verify)

    sent: dict[str, dict] = {}

    def remember(status: int | None, body) -> str | None:
        if isinstance(body, dict) and body.get("requestId"):
            sent[body["requestId"]] = {"status": "SUCCEEDED" if status == 200 else "FAILED", "httpStatus": status}
            return body["requestId"]
        return None

    # 1. success
    text = "tracking e2e: this sentence must not be stored"
    status, body = call("POST", infer, {"capability": "TEXT", "input": text})
    rid = remember(status, body)
    if not check(status == 200 and rid is not None, f"inference without model -> 200 ({status})"):
        print(f"      {body}")
        return summary()
    status, t = call("GET", f"{api}/api/requests/{rid}")
    check(status == 200 and isinstance(t, dict), f"GET /api/requests/{rid} -> 200 ({status})")
    if isinstance(t, dict):
        check(t.get("status") == "SUCCEEDED" and t.get("httpStatus") == 200 and t.get("errorCode") is None,
              f"tracked SUCCEEDED/200 ({t.get('status')}/{t.get('httpStatus')})")
        check(t.get("workerEndpoint") == body.get("workerEndpoint") and t.get("workerId") == body.get("worker"),
              f"tracked worker {t.get('workerEndpoint')}/{t.get('workerId')} = response "
              f"{body.get('workerEndpoint')}/{body.get('worker')}")
        times = [t.get("receivedAt"), t.get("dispatchedAt"), t.get("completedAt")]
        parsed = [datetime.fromisoformat(x.replace("Z", "+00:00")) for x in times if x]
        check(len(parsed) == 3 and parsed == sorted(parsed), f"receivedAt <= dispatchedAt <= completedAt {times}")
        check(t.get("inputChars") == len(text), f"inputChars = {len(text)} ({t.get('inputChars')})")
        check(text not in json.dumps(t), "input text is not stored or returned")

    # 2. invalid request
    status, body = call("POST", infer, {"input": "   "})
    rid = remember(status, body)
    status_t, t = call("GET", f"{api}/api/requests/{rid}")
    check(status == 400 and isinstance(t, dict) and t.get("status") == "FAILED"
          and t.get("errorCode") == "INVALID_REQUEST" and t.get("dispatchedAt") is None,
          f"blank input -> 400, tracked FAILED/INVALID_REQUEST, never dispatched ({status}, {status_t})")

    # 3. unknown model
    missing = "no-such-model-" + uuid.uuid4().hex[:6]
    status, body = call("POST", infer, {"input": "x", "model": missing, "version": "v9"})
    rid = remember(status, body)
    status_t, t = call("GET", f"{api}/api/requests/{rid}")
    check(status == 404 and isinstance(t, dict) and t.get("errorCode") == "MODEL_NOT_FOUND"
          and t.get("requestedModel") == missing and t.get("requestedVersion") == "v9",
          f"unknown model -> 404, tracked FAILED/MODEL_NOT_FOUND with requested model+version ({status})")

    # 4. list order and limit validation
    last_three = []
    for _ in range(3):
        s, b = call("POST", infer, {"input": ""})
        last_three.append(remember(s, b))
    status, rows = call("GET", f"{api}/api/requests?limit=3")
    listed = [r.get("requestId") for r in rows] if isinstance(rows, list) else rows
    check(status == 200 and listed == list(reversed(last_three)), f"limit=3 is newest first ({status})")
    for bad in ("0", "101", "abc"):
        status, _ = call("GET", f"{api}/api/requests?limit={bad}")
        check(status == 400, f"limit={bad} -> 400 ({status})")

    # 5. concurrency
    n = args.concurrent
    with ThreadPoolExecutor(max_workers=n) as pool:
        replies = list(pool.map(lambda i: call("POST", infer, {"input": f"concurrent {i}"}), range(n)))
    ids = [remember(s, b) for s, b in replies]
    check(len(set(ids)) == n and None not in ids, f"{n} concurrent requests -> {len(set(ids))} distinct ids")
    consistent = 0
    for (s, _), i in zip(replies, ids):
        st, t = call("GET", f"{api}/api/requests/{i}")
        want = "SUCCEEDED" if s == 200 else "FAILED"
        if st == 200 and isinstance(t, dict) and t.get("status") == want and t.get("httpStatus") == s:
            consistent += 1
    check(consistent == n, f"every concurrent request has one final row matching its HTTP status ({consistent}/{n})")

    # 6. read errors
    status, _ = call("GET", f"{api}/api/requests/{uuid.uuid4()}")
    check(status == 404, f"unknown requestId -> 404 ({status})")
    status, _ = call("GET", f"{api}/api/requests/not-a-uuid")
    check(status == 400, f"malformed requestId -> 400 ({status})")

    if args.save:
        with open(args.save, "w", encoding="utf-8") as f:
            json.dump(sent, f, indent=1)
        print(f"saved {len(sent)} tracked request ids to {args.save}; restart the backend, then run --verify")
    return summary()


if __name__ == "__main__":
    sys.exit(main())
