#!/usr/bin/env python3
"""Real SIGTERM of worker-b during a staggered burst. Not an artificial HTTP fault."""
import json, os, signal, sys, threading, time, urllib.error, urllib.request

BASE = "http://127.0.0.1:8080"
PID = int(sys.argv[1])
OUT = sys.argv[2]
N = 30
GAP = 0.2
KILL_AT = 0.5


def one(i, t0, rows):
    delay = i * GAP
    if delay:
        time.sleep(delay)
    started = time.time() - t0
    body = json.dumps({"capability": "TEXT", "input": f"kill mid-run {i} wonderful"}).encode()
    req = urllib.request.Request(BASE + "/api/inference", data=body, method="POST",
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=40) as r:
            raw, status = r.read().decode(), r.status
    except urllib.error.HTTPError as e:
        raw, status = e.read().decode(), e.code
    except Exception as e:
        rows[i] = {"i": i, "t": round(started, 3), "http": None, "error": str(e), "endpoint": None, "elapsed": round(time.time() - t0 - started, 3)}
        return
    try:
        parsed = json.loads(raw)
    except ValueError:
        parsed = {}
    rows[i] = {
        "i": i, "t": round(started, 3), "http": status,
        "error": parsed.get("error"), "endpoint": parsed.get("workerEndpoint"),
        "worker": parsed.get("worker"), "elapsed": round(time.time() - t0 - started, 3),
        "id": parsed.get("requestId"),
    }


def main():
    rows = [None] * N
    t0 = time.time()
    threads = [threading.Thread(target=one, args=(i, t0, rows)) for i in range(N)]
    for t in threads:
        t.start()
    time.sleep(KILL_AT)
    kill_t = time.time() - t0
    os.kill(PID, signal.SIGTERM)
    for t in threads:
        t.join()
    counts = {}
    for row in rows:
        key = (row["http"], row["error"], row["endpoint"])
        counts[key] = counts.get(key, 0) + 1
    lines = [f"label=real SIGTERM of worker-b pid {PID}", f"KILL_AT {kill_t:.3f}", f"N {N} GAP {GAP}"]
    for row in rows:
        lines.append("ROW " + json.dumps(row))
    lines.append("COUNTS")
    for key, n in sorted(counts.items(), key=lambda kv: str(kv[0])):
        lines.append(f"  http={key[0]} error={key[1]} endpoint={key[2]} n={n}")
    text = "\n".join(lines) + "\n"
    open(OUT, "w").write(text)
    print(text, end="")


if __name__ == "__main__":
    main()
