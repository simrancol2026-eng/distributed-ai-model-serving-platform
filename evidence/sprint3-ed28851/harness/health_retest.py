#!/usr/bin/env python3
"""HEALTH_LATENCY checks for ed28851. Artificial faults are labeled in the output."""
import json, subprocess, threading, time, urllib.error, urllib.request

BASE = "http://127.0.0.1:8080"
CONTROL = "/tmp/s3ed-run/proxy-control.json"
PROXY_LOG = "/tmp/s3ed-run/logs/proxy.log"
LOG = "/tmp/s3ed-run/logs/spring-hl.log"
OUT = "/tmp/s3ed-run/logs/health-retest.txt"


def say(msg):
    print(msg, flush=True)
    with open(OUT, "a") as f:
        f.write(msg + "\n")


def set_control(spec):
    with open(CONTROL, "w") as f:
        json.dump(spec, f)
    say("CONTROL " + json.dumps(spec))


def call(path, body=None, timeout=40):
    data = None if body is None else json.dumps(body).encode()
    method = "POST" if body is not None else "GET"
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
    return {"http": status, "body": parsed, "elapsed_s": round(time.time() - t, 3)}


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


def cand(b, endpoint):
    return next((c for c in (b or {}).get("candidates") or [] if c.get("endpoint") == endpoint), None)


def wait_both_live(timeout=20):
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


def proxy_health_arrivals(since_mark):
    rows = []
    try:
        lines = open(PROXY_LOG, encoding="utf-8", errors="replace").read().splitlines()
    except FileNotFoundError:
        return rows
    for line in lines:
        try:
            row = json.loads(line)
        except ValueError:
            continue
        if row.get("event") == "arrived" and row.get("port") == 8001 and str(row.get("path", "")).startswith("/health"):
            if row.get("t", 0) >= since_mark:
                rows.append(row)
    return rows


def main():
    open(OUT, "w").write("retest ed28851\n")
    set_control({"8001": {"health": "pass", "predict": "pass"}, "8002": {"health": "pass", "predict": "pass"}})
    both = wait_both_live(20)
    say("BOTH_LIVE " + json.dumps(brief(both)))

    say("SECTION freshness timeline artificial hold")
    # Pin a completed successful /health on 8001, then hold so the next probe
    # (about 2s later) times out at about +4s and the one after that at about +8s.
    mark = time.time()
    deadline = time.time() + 8
    t_success = None
    while time.time() < deadline:
        arrivals = proxy_health_arrivals(mark)
        done = [r for r in arrivals if r.get("mode") == "pass" and r.get("event") == "arrived"]
        # arrival is before the forward; use the forward completion as t=0
        time.sleep(0.05)
        if t_success is None:
            try:
                lines = open(PROXY_LOG, encoding="utf-8", errors="replace").read().splitlines()
            except FileNotFoundError:
                lines = []
            for line in lines:
                try:
                    row = json.loads(line)
                except ValueError:
                    continue
                if (row.get("event") == "forward" and row.get("port") == 8001
                        and str(row.get("path", "")).startswith("/health") and row.get("t", 0) >= mark
                        and row.get("mode") == "pass"):
                    t_success = row["t"]
        if t_success and time.time() - t_success < 0.4:
            break
        if t_success and time.time() - t_success >= 0.4:
            # too late to catch the gap before the next probe; wait for a newer success
            mark = t_success + 0.01
            t_success = None
    if t_success is None:
        say("FRESHNESS_NO_SUCCESS")
    else:
        set_control({"8001": {"health": "hold", "predict": "pass"}, "8002": {"health": "pass", "predict": "pass"}})
        say(f"FRESHNESS_T0 {t_success:.3f} hold_set_dt={time.time() - t_success:.3f}")
        samples = {}
        for mark_s in (5.1, 7.2):
            delay = t_success + mark_s - time.time()
            if delay > 0:
                time.sleep(delay)
            r = infer(f"freshness at {mark_s}")
            b = brief(r)
            a = cand(b, "localhost:8001")
            samples[mark_s] = a
            say(f"FRESHNESS_T+{mark_s} dt={time.time() - t_success:.3f} http={r.get('http')} " + json.dumps(a))
        excluded = None
        deadline = t_success + 12
        while time.time() < deadline:
            r = infer("freshness after second fail")
            b = brief(r)
            a = cand(b, "localhost:8001")
            if a and a.get("live") is False:
                excluded = {"dt": round(time.time() - t_success, 3), "http": r.get("http"),
                            "id": b.get("requestId"), "message": b.get("message"), "error": b.get("error"),
                            "worker": a}
                break
            time.sleep(0.2)
        say("FRESHNESS_EXCLUDED " + json.dumps(excluded))
        arrivals = proxy_health_arrivals(t_success - 0.05)
        say("FRESHNESS_ARRIVALS")
        for row in arrivals:
            say(f"  dt={row.get('t', 0) - t_success:.3f} mode={row.get('mode')} path={row.get('path')}")
        set_control({"8001": {"health": "pass", "predict": "pass"}, "8002": {"health": "pass", "predict": "pass"}})
        recovered = None
        t_rel = time.time()
        deadline = time.time() + 8
        while time.time() < deadline:
            r = infer("freshness after release")
            b = brief(r)
            a = cand(b, "localhost:8001")
            if a and a.get("live"):
                recovered = b
                recovered["seconds_after_release"] = round(time.time() - t_rel, 3)
                break
            time.sleep(0.25)
        say("FRESHNESS_RECOVERED " + json.dumps(recovered))

    say("SECTION two fast failures then one success")
    set_control({"8001": {"health": "pass", "predict": "pass"}, "8002": {"health": "pass", "predict": "pass"}})
    say("BOTH_AGAIN " + json.dumps(brief(wait_both_live(12))))
    t_fail = time.time()
    set_control({"8001": {"health": "fail500", "predict": "pass"}, "8002": {"health": "fail500", "predict": "pass"}})
    first_503 = None
    deadline = time.time() + 12
    while time.time() < deadline:
        r = infer("exclude wonderful")
        if r.get("http") == 503 and isinstance(r.get("body"), dict) and r["body"].get("error") == "NO_HEALTHY_WORKER":
            st = stored(r["body"]["requestId"])
            routing = (st.get("body") or {}).get("routing") if isinstance(st.get("body"), dict) else {}
            lives = [(c.get("endpoint"), c.get("note"), (c.get("health") or {}).get("live"),
                      (c.get("health") or {}).get("healthAgeMs")) for c in (routing or {}).get("candidates") or []]
            if lives and all(x[2] is False for x in lives):
                first_503 = {"http": 503, "id": r["body"]["requestId"], "message": r["body"].get("message"),
                             "elapsed_since_fail500": round(time.time() - t_fail, 3), "stored": lives}
                break
        time.sleep(0.3)
    say("FIRST_ALL_EXCLUDED " + json.dumps(first_503))
    t_ok = time.time()
    set_control({"8001": {"health": "pass", "predict": "pass"}, "8002": {"health": "fail500", "predict": "pass"}})
    readmit = None
    deadline = time.time() + 12
    while time.time() < deadline:
        r = infer("readmit wonderful")
        b = brief(r)
        if r.get("http") == 200 and cand(b, "localhost:8001") and cand(b, "localhost:8001").get("live"):
            readmit = b
            readmit["seconds"] = round(time.time() - t_ok, 3)
            break
        time.sleep(0.25)
    say("READMIT " + json.dumps(readmit))

    set_control({"8001": {"health": "pass", "predict": "pass"}, "8002": {"health": "pass", "predict": "pass"}})
    say("BOTH_AFTER_READMIT " + json.dumps(brief(wait_both_live(12))))

    say("SECTION unserved revision 422")
    created = call("/api/models", {"id": "hl-422-ed28851", "name": "unserved wording", "task": "sentiment-analysis", "capability": "TEXT"})
    say("MODEL_CREATE " + str(created.get("http")) + " " + json.dumps(created.get("body"))[:300])
    v1 = call("/api/models/hl-422-ed28851/versions", {
        "version": "v1", "artifact": "distilbert-base-uncased-finetuned-sst-2-english",
        "artifactRevision": "714eb0fa89d2f80546fda750413ed43d93601a13"})
    say("V1 " + str(v1.get("http")))
    synthetic = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    v2 = call("/api/models/hl-422-ed28851/versions", {
        "version": "v2", "artifact": "distilbert-base-uncased-finetuned-sst-2-english", "artifactRevision": synthetic})
    say("V2 " + str(v2.get("http")) + " synthetic revision, no worker loaded it")
    time.sleep(1.0)
    unserved = infer("unserved revision check", model="hl-422-ed28851", version="v2")
    say("UNSERVED_HTTP " + json.dumps({"http": unserved.get("http"), "body": unserved.get("body")}))
    if isinstance(unserved.get("body"), dict) and unserved["body"].get("requestId"):
        st = stored(unserved["body"]["requestId"])
        say("UNSERVED_STORED " + json.dumps((st.get("body") or {}).get("routing")))
        if first_503:
            say("MESSAGES_DISTINCT " + str(unserved["body"].get("message") != first_503.get("message")))

    say("SECTION cooldown artificial predict 500")
    set_control({"8001": {"health": "pass", "predict": "fail500"}, "8002": {"health": "pass", "predict": "fail500"}})
    time.sleep(1.5)
    for i in range(4):
        r = infer(f"cooldown {i}")
        b = brief(r)
        rid = b.get("requestId")
        if rid:
            st = stored(rid)
            routing = (st.get("body") or {}).get("routing") if isinstance(st.get("body"), dict) else {}
            cands = []
            for c in (routing or {}).get("candidates") or []:
                h = c.get("health") or {}
                cands.append({"endpoint": c.get("endpoint"), "note": c.get("note"), "live": h.get("live"),
                              "cool": h.get("cooldownMs"), "eligible": c.get("eligible")})
            say(f"COOLDOWN_{i} http={r.get('http')} err={b.get('error')} id={rid} stored={json.dumps(cands)} chosen={(routing or {}).get('chosen')}")
        else:
            say(f"COOLDOWN_{i} " + json.dumps(b))

    say("SECTION inflight")
    set_control({"8001": {"health": "pass", "predict": "pass"}, "8002": {"health": "pass", "predict": "pass"}})
    time.sleep(6.2)
    ok = infer("inflight success wonderful")
    nxt = infer("inflight after success")
    say("AFTER_SUCCESS " + json.dumps(brief(ok)))
    say("NEXT_AFTER_SUCCESS " + json.dumps(brief(nxt)))

    set_control({"8001": {"health": "pass", "predict": "fail500"}, "8002": {"health": "pass", "predict": "fail500"}})
    err = infer("inflight artificial 502")
    set_control({"8001": {"health": "pass", "predict": "pass"}, "8002": {"health": "pass", "predict": "pass"}})
    nxt = infer("inflight after 502")
    say("AFTER_502 " + json.dumps({"http": err.get("http"), "body_keys": list((err.get("body") or {}).keys()) if isinstance(err.get("body"), dict) else None, "has_routing": isinstance(err.get("body"), dict) and "routing" in err.get("body"), "id": (err.get("body") or {}).get("requestId") if isinstance(err.get("body"), dict) else None, "error": (err.get("body") or {}).get("error") if isinstance(err.get("body"), dict) else None}))
    if isinstance(err.get("body"), dict) and err["body"].get("requestId"):
        st = stored(err["body"]["requestId"])
        say("AFTER_502_STORED " + json.dumps((st.get("body") or {}).get("routing")))
        lines = [ln for ln in open(LOG, encoding="utf-8", errors="replace") if f"requestId={err['body']['requestId']}" in ln and "routing decision" in ln]
        say("AFTER_502_LOG_LINES " + str(len(lines)))
        for ln in lines:
            say("AFTER_502_LOG " + ln.rstrip())
    say("NEXT_AFTER_502 " + json.dumps(brief(nxt)))

    set_control({"8001": {"health": "pass", "predict": "refuse"}, "8002": {"health": "pass", "predict": "pass"}})
    down = infer("inflight artificial refuse")
    set_control({"8001": {"health": "pass", "predict": "pass"}, "8002": {"health": "pass", "predict": "pass"}})
    nxt = infer("inflight after refuse")
    say("AFTER_REFUSE " + json.dumps(brief(down)))
    say("NEXT_AFTER_REFUSE " + json.dumps(brief(nxt)))

    # Hold the worker that will be chosen. Prefer the lower score; if unknown, hold both predicts
    # one at a time is messy. Hold 8002 predict and pass 8001, but if 8001 is preferred the hold
    # misses. Hold both, then the chosen one is held. Two threads.
    set_control({"8001": {"health": "pass", "predict": "hold"}, "8002": {"health": "pass", "predict": "hold"}})
    time.sleep(0.4)
    done = []

    def fire(i):
        done.append(brief(infer(f"hold slot {i}", timeout=25)))

    threads = [threading.Thread(target=fire, args=(i,)) for i in (1, 2)]
    for t in threads:
        t.start()
    time.sleep(0.8)
    sql = subprocess.run(["sudo", "-u", "postgres", "psql", "-d", "s3_ed28851", "-A", "-t", "-c",
                          "SELECT status || ' ' || worker_endpoint || ' ' || substring(routing_decision from '\"inFlight\":[0-9-]+') FROM inference_requests WHERE status='DISPATCHED' ORDER BY id DESC LIMIT 4;"],
                         capture_output=True, text=True)
    say("DISPATCHED_DURING_HOLD")
    say(sql.stdout.strip())
    set_control({"8001": {"health": "pass", "predict": "pass"}, "8002": {"health": "pass", "predict": "pass"}})
    for t in threads:
        t.join(timeout=20)
    say("HOLD_DONE " + json.dumps(done))
    neg = subprocess.run(["sudo", "-u", "postgres", "psql", "-d", "s3_ed28851", "-A", "-t", "-c",
                          "SELECT count(*) FROM inference_requests WHERE routing_decision LIKE '%\"inFlight\":-%';"],
                         capture_output=True, text=True)
    say("NEGATIVE_INFLIGHT_ROWS " + neg.stdout.strip())

    say("SECTION agreement")
    time.sleep(1.0)
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
