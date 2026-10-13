"""Sprint 2 registry persistence check against a REAL PostgreSQL database.

What it does (Linux/macOS, Python 3.10+, standard library only):
  1. Starts the backend itself (--start-cmd) on --port, with the PLATFORM_DB_* variables from
     your environment, and waits for /api/health.
  2. Confirms from the backend log that the registry datasource is PostgreSQL (not H2).
  3. Registers one new model and two versions under a unique id, and checks the duplicate (409),
     concurrent-duplicate (exactly one 201) and unknown (404) cases.
  4. Saves the GET responses, stops ONLY the backend process it started, and starts it again.
  5. Confirms Flyway did not re-apply migrations on the second start, and that the same records
     come back with identical fields and ids.

It never deletes, truncates or drops anything. Each run adds new rows with a unique id
(persist-check-...), so use a dedicated test database, e.g. created with scripts/setup_registry_db.sh.
The version records use random 40-hex revisions: they are synthetic test metadata, not real models.

Example:
  cd backend/spring-boot && ./mvnw -B -DskipTests package && cd ../..
  export PLATFORM_DB_URL=jdbc:postgresql://localhost:5432/registry_check
  export PLATFORM_DB_USER=registry_check PLATFORM_DB_PASSWORD=...
  python scripts/registry_persistence_check.py --port 8080 \
      --start-cmd "java -jar backend/spring-boot/target/spring-boot-0.0.1-SNAPSHOT.jar"
"""

import argparse
import json
import os
import secrets
import shlex
import signal
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone

results: list[tuple[bool, str]] = []


def check(ok: bool, label: str) -> bool:
    results.append((ok, label))
    print(("PASS " if ok else "FAIL ") + label, flush=True)
    return ok


def call(base: str, method: str, path: str, body: dict | None = None) -> tuple[int, object]:
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(base + path, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read().decode()
            status = r.status
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        status = e.code
    try:
        return status, json.loads(raw)
    except ValueError:
        return status, raw


def port_in_use(base: str) -> bool:
    try:
        urllib.request.urlopen(base + "/api/health", timeout=2)
        return True
    except urllib.error.HTTPError:
        return True
    except OSError:
        return False


def start_backend(cmd: str, port: int, log_path: str) -> subprocess.Popen:
    env = dict(os.environ, PLATFORM_BACKEND_PORT=str(port))
    log = open(log_path, "w")
    # New session = own process group, so stop_backend() can stop exactly this tree and nothing else.
    return subprocess.Popen(shlex.split(cmd), env=env, stdout=log, stderr=subprocess.STDOUT,
                            start_new_session=True)


def wait_healthy(base: str, proc: subprocess.Popen, timeout_s: int) -> bool:
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        if proc.poll() is not None:
            return False
        try:
            with urllib.request.urlopen(base + "/api/health", timeout=2) as r:
                if r.status == 200:
                    return True
        except OSError:
            pass
        time.sleep(1)
    return False


def stop_backend(proc: subprocess.Popen) -> None:
    if proc.poll() is not None:
        return
    os.killpg(proc.pid, signal.SIGTERM)
    try:
        proc.wait(timeout=30)
    except subprocess.TimeoutExpired:
        os.killpg(proc.pid, signal.SIGKILL)
        proc.wait(timeout=10)


def read(path: str) -> str:
    with open(path, encoding="utf-8", errors="replace") as f:
        return f.read()


def datasource_line(log_text: str) -> str:
    lines = [l for l in log_text.splitlines() if "registry datasource:" in l]
    return lines[-1].split("registry datasource:", 1)[1].strip() if lines else ""


def snapshot(base: str, model_id: str) -> dict:
    out = {}
    for path in (f"/api/models/{model_id}", f"/api/models/{model_id}/versions",
                 f"/api/models/{model_id}/versions/v1", f"/api/models/{model_id}/versions/v2"):
        out[path] = call(base, "GET", path)
    listed = call(base, "GET", "/api/models")
    entry = [m for m in listed[1] if isinstance(m, dict) and m.get("id") == model_id] \
        if isinstance(listed[1], list) else []
    out["/api/models (entry)"] = (listed[0], entry)
    return out


def concurrent_create(base: str, body: dict, n: int) -> list[int]:
    statuses: list[int] = []
    gate = threading.Barrier(n)

    def worker():
        gate.wait()
        statuses.append(call(base, "POST", "/api/models", body)[0])

    threads = [threading.Thread(target=worker) for _ in range(n)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    return statuses


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--start-cmd", required=True, help="command that starts the backend (run twice)")
    ap.add_argument("--port", type=int, default=int(os.getenv("PLATFORM_BACKEND_PORT", "8080")))
    ap.add_argument("--log-dir", default=".", help="where backend-run1.log / backend-run2.log are written")
    ap.add_argument("--startup-timeout", type=int, default=180)
    args = ap.parse_args()

    base = f"http://127.0.0.1:{args.port}"
    if not os.getenv("PLATFORM_DB_URL"):
        print("PLATFORM_DB_URL is not set; refusing to guess which database to write to.")
        return 2
    if port_in_use(base):
        print(f"Something already answers on port {args.port}. Stop it or choose --port; "
              "this script only restarts a backend it started itself.")
        return 2

    stamp = datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S")
    model_id = f"persist-check-{stamp}-{secrets.token_hex(3)}"
    race_id = model_id.replace("persist-check", "race-check")
    rev1, rev2 = secrets.token_hex(20), secrets.token_hex(20)
    logs = [os.path.join(args.log_dir, f"backend-run{i}.log") for i in (1, 2)]
    print(f"model id: {model_id}  (synthetic test record; not deleted afterwards)")

    # ---- run 1 ----
    proc = start_backend(args.start_cmd, args.port, logs[0])
    try:
        if not check(wait_healthy(base, proc, args.startup_timeout), "run 1: backend started and /api/health is 200"):
            print(read(logs[0])[-3000:])
            return 1
        log1 = read(logs[0])
        ds = datasource_line(log1)
        check(ds.startswith("product=PostgreSQL"), f"run 1: registry datasource is PostgreSQL ({ds or 'line not found'})")
        flyway = [l.strip() for l in log1.splitlines() if "Successfully applied" in l or "up to date" in l]
        print("info run 1 Flyway: " + (flyway[-1] if flyway else "no Flyway summary line found"))

        model = {"id": model_id, "name": "Persistence check model", "task": "sentiment-analysis",
                 "capability": "TEXT", "description": "synthetic record created by registry_persistence_check.py"}
        check(call(base, "POST", "/api/models", model)[0] == 201, "create model -> 201")
        v1 = {"version": "v1", "artifact": "example/synthetic-artifact", "artifactRevision": rev1, "framework": "none"}
        v2 = {"version": "v2", "artifact": "example/synthetic-artifact", "artifactRevision": rev2, "framework": "none"}
        check(call(base, "POST", f"/api/models/{model_id}/versions", v1)[0] == 201, "create version v1 -> 201")
        check(call(base, "POST", f"/api/models/{model_id}/versions", v2)[0] == 201, "create version v2 -> 201")

        s, b = call(base, "POST", "/api/models", model)
        check(s == 409 and isinstance(b, dict) and b.get("error") == "MODEL_ALREADY_EXISTS", f"duplicate model -> 409 MODEL_ALREADY_EXISTS ({s})")
        s, b = call(base, "POST", f"/api/models/{model_id}/versions", dict(v1, artifactRevision=rev2))
        check(s == 409 and isinstance(b, dict) and b.get("error") == "VERSION_ALREADY_EXISTS", f"duplicate label -> 409 VERSION_ALREADY_EXISTS ({s})")
        s, b = call(base, "POST", f"/api/models/{model_id}/versions", dict(v1, version="v3"))
        check(s == 409 and isinstance(b, dict) and b.get("error") == "DUPLICATE_ARTIFACT", f"same artifact+revision, new label -> 409 DUPLICATE_ARTIFACT ({s})")
        s, b = call(base, "POST", f"/api/models/{model_id}/versions", dict(v1, version="v4", artifactRevision=rev1[:7]))
        check(s == 400, f"abbreviated revision -> 400 ({s})")
        check(call(base, "GET", f"/api/models/{model_id}-missing")[0] == 404, "unknown model -> 404")
        check(call(base, "GET", f"/api/models/{model_id}/versions/v9")[0] == 404, "unknown version -> 404")

        statuses = concurrent_create(base, dict(model, id=race_id), 8)
        check(sorted(statuses) == [201] + [409] * 7, f"8 concurrent identical creates -> one 201, seven 409 ({sorted(statuses)})")

        before = snapshot(base, model_id)
        detail = before[f"/api/models/{model_id}"][1]
        check(isinstance(detail, dict) and (detail.get("latestVersion") or {}).get("version") == "v2",
              "latestVersion is the most recently registered (v2)")
    finally:
        stop_backend(proc)
    check(proc.poll() is not None, "run 1: backend process stopped")

    # ---- run 2: same database, fresh backend process ----
    proc = start_backend(args.start_cmd, args.port, logs[1])
    try:
        if not check(wait_healthy(base, proc, args.startup_timeout), "run 2: backend restarted and /api/health is 200"):
            print(read(logs[1])[-3000:])
            return 1
        log2 = read(logs[1])
        ds = datasource_line(log2)
        check(ds.startswith("product=PostgreSQL"), f"run 2: registry datasource is PostgreSQL ({ds or 'line not found'})")
        check("Successfully applied" not in log2, "run 2: Flyway applied no migrations (schema kept, not recreated)")
        after = snapshot(base, model_id)
        for path in before:
            check(before[path] == after[path], f"after restart, identical response: GET {path}")
        check(after[f"/api/models/{model_id}"][0] == 200, "after restart, model still exists")
    finally:
        stop_backend(proc)

    failed = [label for ok, label in results if not ok]
    print(f"\n{len(results) - len(failed)}/{len(results)} checks passed. Logs: {', '.join(logs)}")
    print(f"Inspect directly: psql ... -c \"SELECT * FROM model_versions WHERE model_id = '{model_id}'\"")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
