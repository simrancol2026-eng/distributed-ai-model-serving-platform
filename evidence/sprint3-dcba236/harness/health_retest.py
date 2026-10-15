#!/usr/bin/env python3
"""Affected HEALTH_LATENCY checks plus a warmed slow-worker load. Artificial faults are labeled."""
import json, subprocess, threading, time, urllib.error, urllib.request

BASE = "http://127.0.0.1:8080"
CONTROL = "/tmp/s3dc-run/proxy-control.json"
LOG = "/tmp/s3dc-run/logs/spring-hl.log"
OUT = "/tmp/s3dc-run/logs/health-retest.txt"
MODEL = None  # filled after registry create


def say(msg):
    print(msg, flush=True)
    with open(OUT, "a") as f:
        f.write(msg + "\n")


def set_control(spec):
    with open(CONTROL, "w") as f:
        json.dump(spec, f)
    say("CONTROL " + json.dumps(spec))


def call(path, body=None, timeout=40, method=None):
    data = None if body is None else json.dumps(body).encode()
    method = method or ("POST" if body is not None else "GET")
    req = urllib.request.Request(BASE + path, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    t = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw, status = r.read().decode(), r.status
    except urllib.error.HTTPError as e:
        raw, status = e.read().decode(), e.code
    except Exception as e:
        return {"http": None, "error": str(e), "elapsed_s": round(time.time() - t, 3)}
    try:
        parsed = json.loads(raw)
    except ValueError:
        parsed = raw
    return {"http": status, "body": parsed, "elapsed_s": round(time.time() - t, 3), "at": time.time()}


def infer(text="health check wonderful", model=None, version=None, timeout=40):
    body = {"capability": "TEXT", "input": text}
    if model:
        body["model"] = model
    if version:
        body["version"] = version
    return call("/api/inference", body, timeout=timeout)


def brief(result):
    b = result.get("body")
    if not isinstance(b, dict):
        return result
    routing = b.get("routing") or {}
    cands = []
    for c in routing.get("candidates") or []:
        h = c.get("health") or {}
        cands.append({
            "endpoint": c.get("endpoint"), "note": c.get("note"), "eligible": c.get("eligible"),
            "inFlight": c.get("inFlight"), "live": h.get("live"), "healthAgeMs": h.get("healthAgeMs"),
            "ewmaMs": h.get("ewmaMs"), "samples": h.get("samples"), "sampleAgeMs": h.get("sampleAgeMs"),
            "estimateMs": h.get("estimateMs"), "score": h.get("score"), "cooldownMs": h.get("cooldownMs"),
        })
    return {
        "http": result.get("http"), "elapsed_s": result.get("elapsed_s"), "requestId": b.get("requestId"),
        "error": b.get("error"), "message": b.get("message"), "worker": b.get("worker"),
        "endpoint": b.get("workerEndpoint"), "totalMs": b.get("totalLatencyMs"),
        "policy": routing.get("policy"), "strategy": routing.get("strategy"),
        "chosen": routing.get("chosen"), "reason": routing.get("reason"), "candidates": cands,
        "label": (b.get("prediction") or {}).get("label"),
    }


def stored(request_id):
    return call(f"/api/requests/{request_id}")


def wait_both_live(timeout=15):
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        last = infer("wait both live")
        b = brief(last)
        lives = [c.get("live") for c in b.get("candidates") or []]
        if last.get("http") == 200 and lives and all(lives):
            return last
        time.sleep(0.3)
    return last


def main():
    open(OUT, "w").write("retest\n")
    set_control({"8001": {"health": "pass", "predict": "pass"}, "8002": {"health": "pass", "predict": "pass"}})
    both = wait_both_live(20)
    say("BOTH_LIVE " + json.dumps(brief(both)))

    say("SECTION two failures then one success")
    t_fail = time.time()
    set_control({"8001": {"health": "fail500", "predict": "pass"}, "8002": {"health": "fail500", "predict": "pass"}})
    first_503 = None
    deadline = time.time() + 12
    while time.time() < deadline:
        r = infer("exclude wonderful")
        b = brief(r)
        if r.get("http") == 503 and isinstance(r.get("body"), dict) and r["body"].get("error") == "NO_HEALTHY_WORKER":
            st = stored(r["body"]["requestId"])
            sb = st.get("body") if isinstance(st.get("body"), dict) else {}
            routing = sb.get("routing") or {}
            lives = [(c.get("endpoint"), c.get("note"), (c.get("health") or {}).get("live"),
                      (c.get("health") or {}).get("healthAgeMs")) for c in routing.get("candidates") or []]
            if lives and all(x[2] is False for x in lives):
                first_503 = {"http": 503, "id": r["body"]["requestId"], "message": r["body"].get("message"),
                             "elapsed_since_fail500": round(time.time() - t_fail, 3), "stored": lives,
                             "reason": routing.get("reason"), "policy": routing.get("policy")}
                break
        time.sleep(0.35)
    say("FIRST_ALL_EXCLUDED " + json.dumps(first_503))
    t_ok = time.time()
    set_control({"8001": {"health": "pass", "predict": "pass"}, "8002": {"health": "fail500", "predict": "pass"}})
    readmit = None
    deadline = time.time() + 12
    while time.time() < deadline:
        r = infer("readmit wonderful")
        b = brief(r)
        if r.get("http") == 200:
            readmit = b
            readmit["seconds"] = round(time.time() - t_ok, 3)
            break
        time.sleep(0.25)
    say("READMIT " + json.dumps(readmit))

    set_control({"8001": {"health": "pass", "predict": "pass"}, "8002": {"health": "pass", "predict": "pass"}})
    say("BOTH_AGAIN " + json.dumps(brief(wait_both_live(12))))

    say("SECTION slow probe t+4 t+5.1 and beyond")
    set_control({"8001": {"health": "hold", "predict": "pass"}, "8002": {"health": "pass", "predict": "pass"}})
    time.sleep(0.3)
    set_control({"8001": {"health": "pass", "predict": "pass"}, "8002": {"health": "pass", "predict": "pass"}})
    seen = None
    t_success = time.time()
    deadline = time.time() + 8
    while time.time() < deadline:
        r = infer("slow baseline wonderful")
        b = brief(r)
        a = next((c for c in b.get("candidates") or [] if c["endpoint"] == "localhost:8001"), None)
        if r.get("http") == 200 and a and a.get("live") and a.get("healthAgeMs") is not None and a["healthAgeMs"] < 1500:
            seen = b
            t_success = time.time() - (a["healthAgeMs"] / 1000.0)
            break
        time.sleep(0.2)
    say("SLOW_BASELINE " + json.dumps(seen))
    say(f"SLOW_T_SUCCESS {t_success:.3f}")
    set_control({"8001": {"health": "hold", "predict": "pass"}, "8002": {"health": "pass", "predict": "pass"}})
    for mark in (4.4, 5.1, 7.0):
        delay = t_success + mark - time.time()
        if delay > 0:
            time.sleep(delay)
        r = infer(f"slow at {mark}")
        say(f"SLOW_T+{mark} dt={time.time() - t_success:.3f} " + json.dumps(brief(r)))
    set_control({"8001": {"health": "pass", "predict": "pass"}, "8002": {"health": "pass", "predict": "pass"}})
    recovered = None
    t_rel = time.time()
    deadline = time.time() + 8
    while time.time() < deadline:
        r = infer("slow after release")
        b = brief(r)
        a = next((c for c in b.get("candidates") or [] if c["endpoint"] == "localhost:8001"), None)
        if a and a.get("live"):
            recovered = b
            recovered["seconds_after_release"] = round(time.time() - t_rel, 3)
            break
        time.sleep(0.3)
    say("SLOW_RECOVERED " + json.dumps(recovered))

    say("SECTION unserved revision 422")
    # register a model whose v1 is real and v2 is a revision no worker serves
    created = call("/api/models", {"id": "hl-422-dcba", "name": "unserved wording", "task": "sentiment-analysis", "capability": "TEXT"})
    say("MODEL_CREATE " + str(created.get("http")) + " " + json.dumps(created.get("body"))[:300])
    v1 = call("/api/models/hl-422-dcba/versions", {
        "version": "v1",
        "artifact": "distilbert-base-uncased-finetuned-sst-2-english",
        "artifactRevision": "714eb0fa89d2f80546fda750413ed43d93601a13"})
    say("V1 " + str(v1.get("http")))
    synthetic = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    v2 = call("/api/models/hl-422-dcba/versions", {
        "version": "v2", "artifact": "distilbert-base-uncased-finetuned-sst-2-english", "artifactRevision": synthetic})
    say("V2 " + str(v2.get("http")) + " synthetic revision, no worker loaded it")
    time.sleep(2.5)
    unserved = infer("unserved revision check", model="hl-422-dcba", version="v2")
    say("UNSERVED_HTTP " + json.dumps({"http": unserved.get("http"), "body": unserved.get("body")}))
    if isinstance(unserved.get("body"), dict) and unserved["body"].get("requestId"):
        st = stored(unserved["body"]["requestId"])
        say("UNSERVED_STORED " + json.dumps((st.get("body") or {}).get("routing")))

    say("SECTION cooldown artificial predict 500")
    set_control({"8001": {"health": "pass", "predict": "fail500"}, "8002": {"health": "pass", "predict": "fail500"}})
    time.sleep(2.2)
    ids = []
    for i in range(4):
        r = infer(f"cooldown {i}")
        b = brief(r)
        rid = b.get("requestId")
        ids.append(rid)
        if rid:
            st = stored(rid)
            routing = (st.get("body") or {}).get("routing") if isinstance(st.get("body"), dict) else {}
            cands = []
            for c in (routing or {}).get("candidates") or []:
                h = c.get("health") or {}
                cands.append({"endpoint": c.get("endpoint"), "note": c.get("note"), "live": h.get("live"),
                              "cool": h.get("cooldownMs"), "eligible": c.get("eligible")})
            say(f"COOLDOWN_{i} http={r.get('http')} err={b.get('error')} msg={b.get('message')} stored={json.dumps(cands)} chosen={routing.get('chosen') if routing else None}")
        else:
            say(f"COOLDOWN_{i} " + json.dumps(b))

    say("SECTION inflight")
    set_control({"8001": {"health": "pass", "predict": "pass"}, "8002": {"health": "pass", "predict": "pass"}})
    time.sleep(6.2)  # let the 5s cooldowns expire
    ok = infer("inflight success wonderful")
    nxt = infer("inflight after success")
    say("AFTER_SUCCESS " + json.dumps(brief(ok)))
    say("NEXT_AFTER_SUCCESS " + json.dumps(brief(nxt)))

    set_control({"8001": {"health": "pass", "predict": "fail500"}, "8002": {"health": "fail500", "predict": "pass"}})
    time.sleep(2.4)
    err = infer("inflight 502")
    set_control({"8001": {"health": "pass", "predict": "pass"}, "8002": {"health": "fail500", "predict": "pass"}})
    time.sleep(0.4)
    nxt = infer("inflight after 502")
    say("AFTER_502 " + json.dumps(brief(err)))
    say("NEXT_AFTER_502 " + json.dumps(brief(nxt)))

    set_control({"8001": {"health": "pass", "predict": "refuse"}, "8002": {"health": "fail500", "predict": "pass"}})
    time.sleep(2.2)
    down = infer("inflight 503")
    set_control({"8001": {"health": "pass", "predict": "pass"}, "8002": {"health": "fail500", "predict": "pass"}})
    time.sleep(0.3)
    nxt = infer("inflight after 503")
    say("AFTER_503 " + json.dumps(brief(down)))
    say("NEXT_AFTER_503 " + json.dumps(brief(nxt)))

    set_control({"8001": {"health": "pass", "predict": "hold"}, "8002": {"health": "fail500", "predict": "pass"}})
    time.sleep(2.4)
    done = []
    def fire(i):
        done.append(brief(infer(f"hold slot {i}", timeout=25)))
    threads = [threading.Thread(target=fire, args=(i,)) for i in (1, 2)]
    for t in threads:
        t.start()
    time.sleep(0.8)
    sql = subprocess.run(["sudo", "-u", "postgres", "psql", "-d", "s3_dcba236", "-A", "-t", "-c",
                          "SELECT status || ' ' || worker_endpoint || ' ' || substring(routing_decision from '\"inFlight\":[0-9-]+') FROM inference_requests WHERE status='DISPATCHED' ORDER BY id DESC LIMIT 4;"],
                         capture_output=True, text=True)
    say("DISPATCHED_DURING_HOLD")
    say(sql.stdout.strip())
    set_control({"8001": {"health": "pass", "predict": "pass"}, "8002": {"health": "pass", "predict": "pass"}})
    for t in threads:
        t.join(timeout=20)
    say("HOLD_DONE " + json.dumps(done))
    neg = subprocess.run(["sudo", "-u", "postgres", "psql", "-d", "s3_dcba236", "-A", "-t", "-c",
                          "SELECT count(*) FROM inference_requests WHERE routing_decision LIKE '%\"inFlight\":-%';"],
                         capture_output=True, text=True)
    say("NEGATIVE_INFLIGHT_ROWS " + neg.stdout.strip())

    say("SECTION agreement")
    time.sleep(2.5)
    ok = infer("agreement wonderful movie")
    b = ok.get("body") if isinstance(ok.get("body"), dict) else {}
    rid = b.get("requestId")
    st = stored(rid) if rid else {}
    stored_body = st.get("body") if isinstance(st.get("body"), dict) else {}
    say("AGREE_EQUAL " + str(b.get("routing") == stored_body.get("routing")))
    say("AGREE_RESPONSE " + json.dumps(b.get("routing")))
    if rid:
        lines = [ln for ln in open(LOG, encoding="utf-8", errors="replace") if f"requestId={rid}" in ln and "routing decision" in ln]
        say("AGREE_LOG_LINES " + str(len(lines)))
        for ln in lines:
            say("AGREE_LOG " + ln.rstrip())

    say("SECTION warmed slow worker")
    set_control({"8001": {"health": "pass", "predict": "pass"}, "8002": {"health": "pass", "predict": "pass"}})
    time.sleep(6.5)
    warm = []
    for i in range(8):
        warm.append(brief(infer(f"warm {i} wonderful")))
    say("WARM_SAMPLES")
    for row in warm:
        say("  " + json.dumps(row))
    set_control({"8001": {"health": "pass", "predict": "pass"}, "8002": {"health": "pass", "predict": "delay:2.0"}})
    time.sleep(0.2)
    n = 8
    results = [None] * n
    barrier = threading.Barrier(n)
    def run(i):
        barrier.wait()
        results[i] = brief(infer(f"warmed load {i} wonderful", timeout=30))
    threads = [threading.Thread(target=run, args=(i,)) for i in range(n)]
    t0 = time.time()
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    say(f"WARMED_LOAD_WALL {round(time.time() - t0, 3)}")
    for i, row in enumerate(results):
        say(f"WARMED_{i} " + json.dumps(row))
    say("DONE")


if __name__ == "__main__":
    main()
