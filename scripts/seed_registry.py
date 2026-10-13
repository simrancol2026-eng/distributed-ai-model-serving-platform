"""Registers the model that the running worker actually serves (Sprint 2 registry seed).

The artifact revision is NOT typed in by hand: it is read from a real POST /api/inference response,
whose "version" field is the Hugging Face commit the worker loaded. If that is not a full 40-hex
revision, nothing is registered. Safe to re-run: an existing model/version gives 409 and is reported.

Usage (backend + worker running, standard library only):
  python scripts/seed_registry.py [--base-url http://localhost:8080]
"""

import argparse
import json
import re
import sys
import urllib.error
import urllib.request

MODEL_ID = "sentiment-distilbert"


def call(base: str, method: str, path: str, body: dict | None = None) -> tuple[int, dict]:
    req = urllib.request.Request(base + path, data=None if body is None else json.dumps(body).encode(),
                                 method=method, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", default="http://localhost:8080")
    base = ap.parse_args().base_url.rstrip("/")

    status, inf = call(base, "POST", "/api/inference", {"capability": "TEXT", "input": "Registry seed check."})
    if status != 200:
        print(f"Inference failed ({status}): {inf}. Start the worker and backend first.")
        return 1
    revision, artifact = inf.get("version") or "", inf.get("model") or ""
    if not re.fullmatch(r"[0-9a-f]{40}", revision):
        print(f"Worker reported version {revision!r}, not a full 40-hex revision; not registering.")
        return 1
    print(f"Worker {inf.get('worker')} serves {artifact} at revision {revision}")

    status, body = call(base, "POST", "/api/models", {
        "id": MODEL_ID, "name": "DistilBERT SST-2 sentiment", "task": inf.get("task") or "sentiment-analysis",
        "capability": "TEXT", "description": "First demo text workload (Phase 1 worker model-a)."})
    print(f"POST /api/models -> {status} {body.get('error', '')}")
    status, body = call(base, "POST", f"/api/models/{MODEL_ID}/versions", {
        "version": "v1", "artifact": artifact, "artifactRevision": revision, "framework": "transformers",
        "notes": "Revision as reported by the running worker."})
    print(f"POST /api/models/{MODEL_ID}/versions -> {status} {body.get('error', '')}")
    return 0 if status in (201, 409) else 1


if __name__ == "__main__":
    sys.exit(main())
