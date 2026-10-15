#!/usr/bin/env python3
"""Print one live routing decision for a policy argument.

Reads no secrets. Talks to the local backend and the Spring log only.
One POST per process. Not an acceptance suite.
"""
import json
import subprocess
import sys
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

policy = sys.argv[1]
log_path = Path(f"/tmp/s3-39-run/logs/spring-{policy}.log")
repo = Path("/tmp/s3-39e59e7")

def sh(*args: str) -> str:
    return subprocess.check_output(args, cwd=repo, text=True).strip()

print("===== IDENTITY =====")
print("git_rev_parse_HEAD", sh("git", "rev-parse", "HEAD"))
print("utc", datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"))
print("policy_arg", policy)
print("spring_log", log_path)

def post(text: str) -> dict:
    body = json.dumps({"capability": "TEXT", "input": text}).encode()
    req = urllib.request.Request(
        "http://127.0.0.1:8080/api/inference",
        data=body,
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=60) as resp:
        raw = resp.read()
        print("post_http", resp.status)
    return json.loads(raw)

def get(rid: str) -> dict:
    with urllib.request.urlopen(f"http://127.0.0.1:8080/api/requests/{rid}", timeout=20) as resp:
        print("get_http", resp.status, "requestId", rid)
        return json.loads(resp.read())

stamp = datetime.now(timezone.utc).strftime("%H%M%S")
prompt = f"{policy} api evidence {stamp}"
print("===== POST /api/inference =====")
print("input", prompt)
posted = post(prompt)
rid = posted["requestId"]
print(json.dumps(posted, indent=2))

print("===== GET /api/requests/%s =====" % rid)
fetched = get(rid)
print(json.dumps(fetched, indent=2))

print("===== SPRING LOG LINE =====")
text = log_path.read_text(errors="replace") if log_path.exists() else ""
matches = [ln for ln in text.splitlines() if f"routing decision requestId={rid}" in ln]
print("log_matches", len(matches))
for ln in matches:
    print(ln)

print("===== OTHER ROUTING LINES IN THIS SPRING LOG =====")
lines = [ln for ln in text.splitlines() if "routing decision requestId=" in ln]
for ln in lines:
    print(ln)

print("===== REQUEST_IDS =====")
ids = []
for ln in lines:
    part = ln.split("requestId=", 1)[1].split(" ", 1)[0]
    ids.append(part)
print("all_ids_in_log", ",".join(ids))
print("api_request_id", rid)
print("END", policy)
