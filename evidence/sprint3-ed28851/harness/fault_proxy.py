#!/usr/bin/env python3
"""Artificial fault proxy in front of the real DistilBERT workers.

Listens on 8001 -> 127.0.0.1:8011 and 8002 -> 127.0.0.1:8012.
Control file JSON, re-read on every request:
  {"8001": {"health": "pass", "predict": "pass"}, "8002": {...}}
health/predict values:
  pass
  hold          block until the mode changes (artificial)
  fail500       artificial HTTP 500
  refuse        close the socket without a response (artificial)
  delay:SECONDS artificial sleep, then pass
  mismatch      predict only: rewrite version to a revision no worker loaded
Every request is appended to the log as one JSON line.
"""
import json
import socket
import sys
import threading
import time

CONTROL = sys.argv[1]
LOG = sys.argv[2]
LISTEN = {8001: ("127.0.0.1", 8011), 8002: ("127.0.0.1", 8012)}
log_lock = threading.Lock()


def control():
    try:
        with open(CONTROL) as f:
            return json.load(f)
    except Exception:
        return {}


def log(row):
    row["t"] = time.time()
    with log_lock:
        with open(LOG, "a") as f:
            f.write(json.dumps(row) + "\n")


def read_headers(conn):
    buf = b""
    while b"\r\n\r\n" not in buf:
        chunk = conn.recv(65536)
        if not chunk:
            return None, None, b""
        buf += chunk
        if len(buf) > 2_000_000:
            break
    head, _, rest = buf.partition(b"\r\n\r\n")
    lines = head.decode("iso-8859-1", "replace").split("\r\n")
    headers = {}
    for line in lines[1:]:
        if ":" in line:
            k, v = line.split(":", 1)
            headers[k.strip().lower()] = v.strip()
    return lines[0], headers, rest


def read_body(conn, headers, rest):
    if "content-length" in headers:
        need = int(headers["content-length"])
        body = rest
        while len(body) < need:
            chunk = conn.recv(65536)
            if not chunk:
                break
            body += chunk
        return body[:need]
    if headers.get("transfer-encoding", "").lower() == "chunked":
        data = rest
        out = b""
        while True:
            while b"\r\n" not in data:
                chunk = conn.recv(65536)
                if not chunk:
                    return out
                data += chunk
            line, data = data.split(b"\r\n", 1)
            size = int(line.split(b";", 1)[0], 16)
            if size == 0:
                return out
            while len(data) < size + 2:
                chunk = conn.recv(65536)
                if not chunk:
                    return out
                data += chunk
            out += data[:size]
            data = data[size + 2:]
    return rest


def read_response(conn):
    line, headers, rest = read_headers(conn)
    if line is None:
        return None
    body = read_body(conn, headers, rest)
    return line, headers, body


def mode_for(port, path):
    cfg = control().get(str(port), {})
    kind = "health" if path.startswith("/health") else "predict" if path.startswith("/predict") else "other"
    return kind, str(cfg.get(kind, "pass"))


def apply_mode(port, path, mode):
    """Return (action, extra). action is pass|fail500|refuse."""
    if mode == "hold":
        started = time.time()
        while True:
            kind, current = mode_for(port, path)
            if current != "hold":
                log({"event": "hold_released", "port": port, "path": path, "held_s": round(time.time() - started, 3), "next": current})
                mode = current
                break
            if time.time() - started > 40:
                log({"event": "hold_timeout", "port": port, "path": path})
                return "fail500", 0
            time.sleep(0.05)
    if mode == "fail500":
        return "fail500", 0
    if mode == "refuse":
        return "refuse", 0
    if mode.startswith("delay:"):
        seconds = float(mode.split(":", 1)[1])
        time.sleep(seconds)
        return "pass", seconds
    return "pass", 0


def forward(listen_port, upstream, request_line, headers, body, mismatch):
    payload = body
    # rebuild request with Content-Length
    path = request_line.split(" ")[1]
    method = request_line.split(" ")[0]
    req = f"{method} {path} HTTP/1.1\r\nHost: {upstream[0]}:{upstream[1]}\r\nContent-Length: {len(payload)}\r\nConnection: close\r\n"
    if "content-type" in headers:
        req += f"Content-Type: {headers['content-type']}\r\n"
    req += "\r\n"
    sock = socket.create_connection(upstream, timeout=60)
    sock.settimeout(60)
    try:
        sock.sendall(req.encode("iso-8859-1") + payload)
        line, resp_headers, resp_body = read_response(sock)
    finally:
        sock.close()
    if mismatch and path.startswith("/predict") and resp_body:
        try:
            doc = json.loads(resp_body)
            doc["version"] = "0000000000000000000000000000000000000000"
            resp_body = json.dumps(doc).encode()
        except Exception as e:
            log({"event": "mismatch_rewrite_failed", "error": str(e)})
    out_headers = [
        line,
        "Content-Length: " + str(len(resp_body)),
        "Content-Type: " + resp_headers.get("content-type", "application/json"),
        "Connection: close",
        "",
        "",
    ]
    return "\r\n".join(out_headers).encode("iso-8859-1") + resp_body, line


def handle(listen_port, conn):
    started = time.time()
    try:
        request_line, headers, rest = read_headers(conn)
        if request_line is None:
            return
        body = read_body(conn, headers, rest)
        path = request_line.split(" ")[1] if " " in request_line else ""
        kind, mode = mode_for(listen_port, path)
        log({"event": "arrived", "port": listen_port, "path": path, "mode": mode, "artificial": mode not in ("pass",)})
        action, extra = apply_mode(listen_port, path, mode)
        if action == "refuse":
            log({"event": "refuse", "port": listen_port, "path": path, "mode": mode, "artificial": True})
            conn.close()
            return
        if action == "fail500":
            msg = b'{"artificial":true,"error":"injected 500"}'
            raw = (
                b"HTTP/1.1 500 Internal Server Error\r\n"
                + f"Content-Length: {len(msg)}\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n".encode()
                + msg
            )
            conn.sendall(raw)
            log({"event": "fail500", "port": listen_port, "path": path, "mode": mode, "artificial": True,
                 "elapsed_s": round(time.time() - started, 3)})
            return
        mismatch = mode == "mismatch"
        raw, status_line = forward(listen_port, LISTEN[listen_port], request_line, headers, body, mismatch)
        conn.sendall(raw)
        log({"event": "forward", "port": listen_port, "path": path, "mode": mode, "status": status_line,
             "mismatch": mismatch, "delay_s": extra, "elapsed_s": round(time.time() - started, 3),
             "artificial": mode not in ("pass",)})
    except Exception as e:
        log({"event": "proxy_error", "port": listen_port, "error": repr(e)})
    finally:
        try:
            conn.close()
        except Exception:
            pass


def serve(port):
    sock = socket.socket()
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(("127.0.0.1", port))
    sock.listen(64)
    log({"event": "listen", "port": port, "upstream": LISTEN[port]})
    while True:
        conn, _ = sock.accept()
        threading.Thread(target=handle, args=(port, conn), daemon=True).start()


def main():
    open(LOG, "a").write(json.dumps({"event": "start", "t": time.time()}) + "\n")
    threads = [threading.Thread(target=serve, args=(p,), daemon=True) for p in LISTEN]
    for t in threads:
        t.start()
    while True:
        time.sleep(3600)


if __name__ == "__main__":
    main()
