"""Registers the model that the running worker actually serves (Sprint 2 registry seed).

The artifact revision is NOT typed in by hand: it is read from a real POST /api/inference response,
whose "version" field is the Hugging Face commit the worker loaded. If that is not a full 40-hex
revision, nothing is registered.

Re-running is safe and never overwrites anything:
  - model or version already registered with the SAME values  -> reported, exit 0
  - model or version already registered with DIFFERENT values -> mismatch reported, exit 1
  - any other failure                                          -> exit 1

Usage (backend + worker running, standard library only):
  python scripts/seed_registry.py [--base-url http://localhost:8080]
Tests: python -m pytest scripts/tests/test_seed_registry.py
"""

import argparse
import json
import re
import sys
import urllib.error
import urllib.request
from typing import Callable

MODEL_ID = "sentiment-distilbert"
VERSION = "v1"

Call = Callable[[str, str, dict | None], tuple[int, dict]]


def http_call(base: str) -> Call:
    def call(method: str, path: str, body: dict | None = None) -> tuple[int, dict]:
        req = urllib.request.Request(base + path, data=None if body is None else json.dumps(body).encode(),
                                     method=method, headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                return r.status, json.loads(r.read() or b"{}")
        except urllib.error.HTTPError as e:
            try:
                return e.code, json.loads(e.read() or b"{}")
            except ValueError:
                return e.code, {}
    return call


def seed(call: Call, log: Callable[[str], None] = print) -> int:
    """Returns the process exit code. `call(method, path, body)` returns (status, json)."""
    status, inf = call("POST", "/api/inference", {"capability": "TEXT", "input": "Registry seed check."})
    if status != 200:
        log(f"FAIL inference returned {status}: {inf}. Start the worker and backend first.")
        return 1
    revision, artifact = inf.get("version") or "", inf.get("model") or ""
    if not re.fullmatch(r"[0-9a-f]{40}", revision):
        log(f"FAIL worker reported version {revision!r}, not a full 40-hex revision; nothing registered.")
        return 1
    log(f"Worker {inf.get('worker')} serves {artifact} at revision {revision}")

    model = {"id": MODEL_ID, "name": "DistilBERT SST-2 sentiment", "task": inf.get("task") or "sentiment-analysis",
             "capability": "TEXT", "description": "First demo text workload (Phase 1 worker model-a)."}
    status, body = call("POST", "/api/models", model)
    if status == 201:
        log(f"created model {MODEL_ID}")
    elif status == 409 and body.get("error") == "MODEL_ALREADY_EXISTS":
        s, existing = call("GET", f"/api/models/{MODEL_ID}", None)
        if s != 200 or existing.get("capability") != "TEXT" or existing.get("task") != model["task"]:
            log(f"FAIL model {MODEL_ID} already exists with different values ({s}): {existing}. Not changed.")
            return 1
        log(f"model {MODEL_ID} already registered with the same capability and task")
    else:
        log(f"FAIL creating model {MODEL_ID}: {status} {body}")
        return 1

    version = {"version": VERSION, "artifact": artifact, "artifactRevision": revision, "framework": "transformers",
               "notes": "Revision as reported by the running worker."}
    status, body = call("POST", f"/api/models/{MODEL_ID}/versions", version)
    if status == 201:
        log(f"created version {MODEL_ID}/{VERSION} -> {artifact}@{revision}")
        return 0
    if status == 409 and body.get("error") == "VERSION_ALREADY_EXISTS":
        s, existing = call("GET", f"/api/models/{MODEL_ID}/versions/{VERSION}", None)
        if s == 200 and existing.get("artifact") == artifact and existing.get("artifactRevision") == revision:
            log(f"version {MODEL_ID}/{VERSION} already registered with the same artifact and revision")
            return 0
        log(f"FAIL {MODEL_ID}/{VERSION} already points to {existing.get('artifact')}@"
            f"{existing.get('artifactRevision')}, but the running worker serves {artifact}@{revision}. "
            "Not overwritten; register the running revision under a new label if it is intended.")
        return 1
    if status == 409 and body.get("error") == "DUPLICATE_ARTIFACT":
        log(f"FAIL {artifact}@{revision} is already registered for {MODEL_ID} under another label "
            f"(not '{VERSION}'): {body.get('message')}")
        return 1
    log(f"FAIL creating version {MODEL_ID}/{VERSION}: {status} {body}")
    return 1


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", default="http://localhost:8080")
    return seed(http_call(ap.parse_args().base_url.rstrip("/")))


if __name__ == "__main__":
    sys.exit(main())
