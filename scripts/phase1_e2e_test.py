#!/usr/bin/env python3
"""Phase 1 end-to-end acceptance test (Tests A-D and F from the Phase 1 plan).

Uses the REAL model and the REAL Spring Boot backend. Standard library only.

Prerequisites
  1. Backend running:  cd backend/spring-boot && ./mvnw spring-boot:run   (Windows: mvnw.cmd ...)
  2. Worker dependencies installed in the Python you run this with:
       pip install -r model-workers/model-a/requirements.txt
  3. Port 8001 free: this script STARTS and STOPS the worker itself (needed for Test F).

Run from the repository root:
  python scripts/phase1_e2e_test.py

Test E (React UI) is manual; instructions are printed at the end.
Results are also written to phase1_e2e_results.json.
"""

import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

BACKEND = os.getenv("BACKEND_URL", "http://localhost:8080")
WORKER = os.getenv("WORKER_URL", "http://localhost:8001")
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WORKER_DIR = os.path.join(ROOT, "model-workers", "model-a")
POSITIVE_TEXT = "I absolutely love this platform, it works wonderfully."
NEGATIVE_TEXT = "This is terrible and I hate it."

results = []


def http(method, url, body=None, headers=None, timeout=60):
    """Returns (status, text). status is None if the server could not be reached."""
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method, headers=headers or {})
    if data is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read().decode(), dict(r.headers)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode(), dict(e.headers)
    except (urllib.error.URLError, ConnectionError, TimeoutError) as e:
        return None, str(e), {}


def record(test, passed, evidence):
    results.append({"test": test, "result": "PASS" if passed else "FAIL", "evidence": evidence})
    print(f"[{'PASS' if passed else 'FAIL'}] {test}\n        {evidence}")


def start_worker():
    proc = subprocess.Popen(
        [sys.executable, "-m", "uvicorn", "worker:app", "--port", WORKER.rsplit(":", 1)[1]],
        cwd=WORKER_DIR,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.STDOUT,
    )
    started = time.time()
    while time.time() - started < 300:  # first start may download the model
        if proc.poll() is not None:
            raise SystemExit(f"Worker process exited early (code {proc.returncode}). Are dependencies installed?")
        status, _, _ = http("GET", WORKER + "/health", timeout=2)
        if status == 200:
            return proc, time.time() - started
        time.sleep(1)
    proc.kill()
    raise SystemExit("Worker did not become healthy within 300 s")


def stop_worker(proc):
    proc.terminate()
    try:
        proc.wait(timeout=15)
    except subprocess.TimeoutExpired:
        proc.kill()


def main():
    status, _, _ = http("GET", WORKER + "/health", timeout=2)
    if status is not None:
        raise SystemExit(f"Something is already listening on {WORKER}. Stop it first; this script manages the worker.")

    print(f"Backend: {BACKEND}\nWorker:  {WORKER} (started by this script)\n")
    worker, startup_s = start_worker()
    print(f"Worker healthy after {startup_s:.1f} s\n")
    try:
        # A - backend health
        s, body, _ = http("GET", BACKEND + "/api/health")
        record("A  Backend health", s == 200 and body == "Backend is healthy", f"HTTP {s} body={body!r}")

        # B - worker health
        s, body, _ = http("GET", WORKER + "/health")
        record("B  Worker health", s == 200 and '"healthy"' in body, f"HTTP {s} body={body}")

        # C - direct worker inference (real model)
        s, body, _ = http("POST", WORKER + "/predict", {"text": POSITIVE_TEXT})
        direct = json.loads(body) if s == 200 else {}
        s2, body2, _ = http("POST", WORKER + "/predict", {"text": NEGATIVE_TEXT})
        direct_neg = json.loads(body2) if s2 == 200 else {}
        record(
            "C  Direct worker inference (real model)",
            s == 200 and direct.get("label") == "POSITIVE" and direct_neg.get("label") == "NEGATIVE",
            f"positive text -> {direct.get('label')} {direct.get('score')}; "
            f"negative text -> {direct_neg.get('label')} {direct_neg.get('score')}; model={direct.get('model')}",
        )

        # D - same input through Spring Boot
        s, body, _ = http("POST", BACKEND + "/api/inference", {"capability": "TEXT", "input": POSITIVE_TEXT})
        via = json.loads(body) if s == 200 else {}
        pred = via.get("prediction") or {}
        record(
            "D  Inference through Spring Boot",
            s == 200 and via.get("status") == "success" and pred.get("label") == direct.get("label"),
            f"HTTP {s} label={pred.get('label')} score={pred.get('score')} worker={via.get('worker')} "
            f"workerMs={via.get('workerInferenceMs')} totalMs={via.get('totalLatencyMs')}",
        )

        # D2 - validation and capability check
        s_blank, _, _ = http("POST", BACKEND + "/api/inference", {"capability": "TEXT", "input": ""})
        s_img, _, _ = http("POST", BACKEND + "/api/inference", {"capability": "IMAGE", "input": "x"})
        record("D2 Validation (blank->400, IMAGE->422)", s_blank == 400 and s_img == 422,
               f"blank={s_blank} image={s_img}")

        # D3 - CORS for the React origin
        _, _, h = http("OPTIONS", BACKEND + "/api/inference", headers={
            "Origin": "http://localhost:5173", "Access-Control-Request-Method": "POST",
            "Access-Control-Request-Headers": "Content-Type"})
        allow = {k.lower(): v for k, v in h.items()}.get("access-control-allow-origin")
        record("D3 CORS allows http://localhost:5173", allow == "http://localhost:5173", f"Allow-Origin={allow}")

        # F - failure: stop worker, expect controlled 503; restart; expect recovery
        stop_worker(worker)
        s, body, _ = http("POST", BACKEND + "/api/inference", {"capability": "TEXT", "input": POSITIVE_TEXT})
        s_health, _, _ = http("GET", BACKEND + "/api/health")
        record("F1 Worker stopped -> controlled error, backend still up",
               s == 503 and "WORKER_UNAVAILABLE" in body and s_health == 200,
               f"inference HTTP {s} body={body}; backend health HTTP {s_health}")

        restart_at = time.time()
        worker, _ = start_worker()
        s, body, _ = http("POST", BACKEND + "/api/inference", {"capability": "TEXT", "input": POSITIVE_TEXT})
        recovery_s = time.time() - restart_at
        record("F2 Worker restarted -> inference works again", s == 200 and '"success"' in body,
               f"HTTP {s}; time from restart to first successful inference = {recovery_s:.1f} s "
               "(manual restart; automatic recovery is a later phase)")
    finally:
        stop_worker(worker)

    passed = sum(r["result"] == "PASS" for r in results)
    print(f"\n{passed}/{len(results)} checks passed.")
    with open("phase1_e2e_results.json", "w") as f:
        json.dump({"timestamp": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "backend": BACKEND,
                   "worker": WORKER, "results": results}, f, indent=2)
    print("Saved phase1_e2e_results.json")
    print("""
Test E (manual, React UI):
  1. Start the worker:   cd model-workers/model-a && uvicorn worker:app --port 8001
  2. cd frontend && copy .env.example to .env.local && npm run dev
  3. Open http://localhost:5173 -> header badge should read "Backend connected".
  4. Choose TEXT (or Auto routing), type a sentence, send.
     Expect e.g. "POSITIVE (confidence 99.9%) - sentiment-analysis", Worker = model-a, a latency value.
  5. Stop the worker and send again -> expect "Request failed (WORKER_UNAVAILABLE) ...", no crash.
""")
    sys.exit(0 if passed == len(results) else 1)


if __name__ == "__main__":
    main()
