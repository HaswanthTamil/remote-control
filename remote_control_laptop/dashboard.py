"""Local dashboard UI for the laptop agent.

Serves a small operator page on http://127.0.0.1:<port> showing live logs,
device identity / pairing state and system vitals. It binds to loopback by
default because it is a local tool, not a network service - exposing
DASHBOARD_HOST to a network is unauthenticated, so don't do it.

The module keeps a process-wide default instance so the agent and auth code
can just call dashboard.log(...) / dashboard.set_status(...); it degrades to
plain prints before init() has run.
"""

import json
import os
import platform
import shutil
import socket
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

from config import DASHBOARD_PORT

INDEX = r"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Laptop agent dashboard</title>
<style>
  :root{--bg:#0b0f14;--panel:#121820;--panel2:#0e141b;--border:#1f2a37;
    --txt:#d7e0ea;--muted:#8a96a5;--ok:#34d399;--warn:#fbbf24;--err:#f87171;
    --acc:#60a5fa;--mono:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;}
  *{box-sizing:border-box}
  body{margin:0;background:var(--bg);color:var(--txt);
    font:14px/1.45 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;padding:20px}
  h1{font-size:16px;margin:0;display:flex;align-items:center;gap:10px;flex-wrap:wrap;font-weight:650}
  h2{font-size:11px;text-transform:uppercase;letter-spacing:.1em;color:var(--muted);margin:0 0 12px}
  .top{display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap;margin-bottom:16px}
  .pill{padding:4px 10px;border-radius:999px;font-size:12px;font-weight:650;border:1px solid var(--muted);color:var(--muted)}
  .pill.online{color:var(--ok);border-color:var(--ok)}
  .pill.auth,.pill.reconnect{color:var(--warn);border-color:var(--warn)}
  .pill.offline{color:var(--err);border-color:var(--err)}
  .dot{display:inline-block;width:8px;height:8px;border-radius:50%;background:currentColor;margin-right:6px}
  .grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(320px,1fr));gap:14px;margin-bottom:16px}
  .card{background:var(--panel);border:1px solid var(--border);border-radius:10px;padding:14px}
  .kv{display:grid;grid-template-columns:138px 1fr;gap:5px 12px;font-size:13px;margin:0}
  .kv dt{color:var(--muted)}
  .kv dd{margin:0;font-family:var(--mono);word-break:break-all}
  .badge{display:inline-block;font-size:11px;padding:2px 8px;border-radius:6px;border:1px solid var(--border);color:var(--muted)}
  .badge.ok{color:var(--ok);border-color:var(--ok)}
  .badge.warn{color:var(--warn);border-color:var(--warn)}
  .flex{display:flex;gap:8px;align-items:center;flex-wrap:wrap}
  .metric{margin-bottom:12px}
  .metric .lbl{display:flex;justify-content:space-between;font-size:12px;margin-bottom:4px;color:var(--muted)}
  .metric .lbl b{color:var(--txt);font-family:var(--mono);font-weight:600}
  .bar{height:8px;background:var(--panel2);border-radius:4px;overflow:hidden}
  .bar>i{display:block;height:100%;background:var(--acc);border-radius:4px;transition:width .5s}
  .logs{background:var(--panel);border:1px solid var(--border);border-radius:10px;overflow:hidden}
  .logs .head{display:flex;justify-content:space-between;align-items:center;padding:10px 14px;border-bottom:1px solid var(--border)}
  pre{margin:0;padding:12px 14px;height:300px;overflow:auto;font-family:var(--mono);font-size:12px;line-height:1.5}
  .line{white-space:pre-wrap}
  .line.err{color:var(--err)}
  .line.ok{color:var(--ok)}
  .line.cmd{color:var(--warn)}
  button.ghost{background:transparent;border:1px solid var(--border);color:var(--muted);border-radius:6px;padding:4px 10px;cursor:pointer;font-size:12px}
  button.ghost:hover{color:var(--txt);border-color:var(--muted)}
  .sub{color:var(--muted);font-size:12px}
</style>
</head>
<body>
<div class="top">
  <h1><span id="pill" class="pill offline"><span class="dot"></span>offline</span> laptop agent &middot; dashboard</h1>
  <div class="sub" id="relay"></div>
</div>

<div class="grid">
  <div class="card">
    <h2>Machine</h2>
    <div id="machine"></div>
  </div>
  <div class="card">
    <h2>Device key</h2>
    <div id="identity"></div>
    <div class="flex" id="badges" style="margin-top:12px"></div>
  </div>
  <div class="card">
    <h2>Session</h2>
    <div id="session"></div>
  </div>
  <div class="card">
    <h2>Vitals</h2>
    <div id="vitals"></div>
  </div>
</div>

<div class="logs">
  <div class="head">
    <span>Logs <span class="sub" id="logcount"></span></span>
    <button class="ghost" id="pause">pause</button>
  </div>
  <pre id="logview"></pre>
</div>

<script>
const $ = s => document.querySelector(s);

function fmt(b) {
  if (b >= 1024 ** 3) return (b / 1024 ** 3).toFixed(1) + " GiB";
  if (b >= 1024 ** 2) return (b / 1024 ** 2).toFixed(1) + " MiB";
  if (b >= 1024) return (b / 1024).toFixed(1) + " KiB";
  return b + " B";
}
const uptime = s => {
  s = Math.floor(s);
  const d = Math.floor(s / 86400); s -= d * 86400;
  const h = Math.floor(s / 3600); s -= h * 3600;
  const m = Math.floor(s / 60);
  return (d ? d + "d " : "") + (h ? h + "h " : "") + m + "m";
};

function pill(kind, txt) {
  const p = $("#pill");
  p.className = "pill " + kind;
  p.innerHTML = `<span class="dot"></span>${txt}`;
}

function fill(el, pairs) {
  const dl = document.createElement("dl");
  dl.className = "kv";
  for (const [k, v] of pairs) {
    const dt = document.createElement("dt"); dt.textContent = k;
    const dd = document.createElement("dd"); dd.textContent = v;
    dl.append(dt, dd);
  }
  el.replaceChildren(dl);
}

function mb(label, val, total) {
  const div = document.createElement("div");
  div.className = "metric";
  const pct = total ? Math.min(100, Math.round(100 * val / total)) : 0;
  div.innerHTML =
    `<div class="lbl"><span>${label}</span><b>${fmt(val)} / ${fmt(total)} &middot; ${pct}%</b></div>` +
    `<div class="bar"><i style="width:${pct}%"></i></div>`;
  return div;
}

async function refreshState() {
  let s;
  try { s = await (await fetch("/api/state")).json(); } catch { return; }

  if (s.connected && s.registered) pill("online", "online");
  else if (s.connected) pill("auth", "authenticating");
  else if (s.reconnecting) pill("reconnect", "reconnecting");
  else pill("offline", "offline");

  $("#relay").textContent = s.relay_url || "";

  fill($("#machine"), [
    ["Hostname", s.hostname || "—"],
    ["Platform", s.platform || "—"],
    ["Python", s.python || "—"],
    ["Agent PID", s.pid || "—"],
    ["Started", s.started_at || "—"],
  ]);

  const pkCur = s.public_key || "—";
  const pk = s.public_key ? s.public_key.slice(0, 8) + "…" + s.public_key.slice(-8) : "—";
  fill($("#identity"), [
    ["Device ID", s.device_id || "—"],
    ["Public key", pk],
  ]);

  const badges = $("#badges");
  badges.replaceChildren();
  const add = (cls, txt) => {
    const el = document.createElement("span");
    el.className = "badge" + (cls ? " " + cls : "");
    el.textContent = txt;
    badges.append(el);
  };
  if (s.paired) add("ok", "paired");
  else add("warn", s.pair_token_needed ? "needs pair token" : "unpaired");

  fill($("#session"), [
    ["Connection", s.connected ? "open" : "closed"],
    ["Registered", s.registered ? "yes" : "no"],
    ["Reconnect", s.reconnecting ? "backoff " + s.backoff + "s" : "—"],
    ["Active command", s.active_command || "—"],
    ["Last error", s.last_error || "—"],
  ]);
}

async function refreshVitals() {
  let v;
  try { v = await (await fetch("/api/vitals")).json(); } catch { return; }

  const host = $("#vitals");
  host.replaceChildren();

  const cpu = document.createElement("div");
  cpu.className = "metric";
  const cpuPct = v.cpu == null ? 0 : v.cpu;
  cpu.innerHTML =
    `<div class="lbl"><span>CPU</span><b>${v.cpu == null ? "—" : v.cpu + "%"} of ${v.cores} cores</b></div>` +
    `<div class="bar"><i style="width:${cpuPct}%"></i></div>`;
  host.append(cpu);

  if (v.mem) host.append(mb("Memory", v.mem.used, v.mem.total));
  if (v.disk) host.append(mb("Disk (/)", v.disk.used, v.disk.total));

  const foot = document.createElement("div");
  foot.className = "sub";
  const load = (v.load || []).join(" / ") || "—";
  foot.textContent = `load ${load} · up ${uptime(v.uptime_s || 0)}`;
  host.append(foot);
}

let since = 0;
let paused = false;

function lineClass(line) {
  if (/disconnected|error|failed|refused|invalid|✗/i.test(line)) return " err";
  if (/connected|registered|paired|dashboard on/i.test(line)) return " ok";
  if (/command/i.test(line)) return " cmd";
  return "";
}

async function refreshLogs() {
  if (paused) return;
  let l;
  try { l = await (await fetch(`/api/logs?since=${since}`)).json(); } catch { return; }

  const pre = $("#logview");
  const nearBottom = pre.scrollHeight - pre.scrollTop - pre.clientHeight < 40;

  for (const [seq, line] of l.entries) {
    const d = document.createElement("div");
    d.className = "line" + lineClass(line);
    d.textContent = line;
    pre.append(d);
    since = seq;
  }
  while (pre.childNodes.length > 2000) pre.removeChild(pre.firstChild);

  $("#logcount").textContent = `(last ${l.total})`;
  if (nearBottom) pre.scrollTop = pre.scrollHeight;
}

$("#pause").addEventListener("click", () => {
  paused = !paused;
  $("#pause").textContent = paused ? "resume" : "pause";
});

refreshState(); refreshVitals(); refreshLogs();
setInterval(refreshState, 2000);
setInterval(refreshVitals, 2000);
setInterval(refreshLogs, 1500);
</script>
</body>
</html>
"""


def _read_file(path):
    try:
        with open(path, encoding="utf-8", errors="ignore") as f:
            return f.read()
    except OSError:
        return ""


class Dashboard:
    def __init__(self, host="127.0.0.1", port=DASHBOARD_PORT):
        self.host = host
        self.port = port
        self._lock = threading.RLock()
        self._logs = []
        self._seq = 0
        self._cpu = {"prev": None}
        self._status = {
            "connected": False,
            "registered": False,
            "reconnecting": True,
            "backoff": 1.0,
            "relay_url": "",
            "device_id": "",
            "public_key": "",
            "paired": False,
            "pair_token_needed": False,
            "active_command": None,
            "last_error": None,
            "hostname": socket.gethostname(),
            "platform": platform.platform(),
            "python": platform.python_version(),
            "pid": os.getpid(),
            "started_at": time.strftime("%Y-%m-%d %H:%M:%S"),
        }

    def log(self, message):
        line = f"[{time.strftime('%H:%M:%S')}] {message}"
        with self._lock:
            self._logs.append((self._seq, line))
            self._seq += 1
            if len(self._logs) > 2000:
                del self._logs[: len(self._logs) - 1000]
        print(message, flush=True)

    def set_status(self, **kwargs):
        with self._lock:
            self._status.update(kwargs)

    def state(self):
        with self._lock:
            return dict(self._status)

    def logs_since(self, since):
        with self._lock:
            entries = [e for e in self._logs if e[0] > since]
            return {"entries": entries, "next": self._seq, "total": self._seq}

    def _cpu_percent(self):
        data = _read_file("/proc/stat").splitlines()
        if not data or not data[0].startswith("cpu"):
            return None
        fields = data[0].split()[1:]
        if len(fields) < 4:
            return None
        idle = int(fields[3]) + (int(fields[4]) if len(fields) > 4 else 0)
        total = sum(int(x) for x in fields)
        with self._lock:
            prev = self._cpu["prev"]
            self._cpu["prev"] = (total, idle)
        if not prev:
            return None
        d_total = total - prev[0]
        d_idle = idle - prev[1]
        if d_total <= 0:
            return None
        return round(100.0 * (d_total - d_idle) / d_total, 1)

    def vitals(self):
        cpu = self._cpu_percent()

        mem_total = mem_avail = None
        for line in _read_file("/proc/meminfo").splitlines():
            parts = line.split()
            if len(parts) >= 2 and parts[0].endswith(":"):
                if parts[0] == "MemTotal:":
                    mem_total = int(parts[1]) * 1024
                elif parts[0] == "MemAvailable:":
                    mem_avail = int(parts[1]) * 1024

        load = _read_file("/proc/loadavg").split()[:3]

        uptime_s = 0
        up = _read_file("/proc/uptime").split()
        if up:
            try:
                uptime_s = float(up[0])
            except ValueError:
                uptime_s = 0

        disk = shutil.disk_usage("/")

        return {
            "cpu": cpu,
            "cores": os.cpu_count() or 1,
            "mem": ({"total": mem_total, "used": mem_total - mem_avail, "available": mem_avail}
                    if mem_total and mem_avail is not None else None),
            "disk": {"total": disk.total, "used": disk.used},
            "load": load,
            "uptime_s": int(uptime_s),
        }

    def _send(self, handler, content_type, body):
        handler.send_response(200)
        handler.send_header("Content-Type", content_type)
        handler.send_header("Cache-Control", "no-store")
        handler.send_header("Content-Length", str(len(body)))
        handler.end_headers()
        handler.wfile.write(body)

    def _handler_class(self):
        dash = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                parsed = urlparse(self.path)

                if parsed.path == "/":
                    dash._send(self, "text/html; charset=utf-8", INDEX.encode("utf-8"))
                    return

                if parsed.path in ("/api/state", "/api/state/"):
                    dash._send(self, "application/json", json.dumps(dash.state()).encode())
                    return

                if parsed.path in ("/api/vitals", "/api/vitals/"):
                    dash._send(self, "application/json", json.dumps(dash.vitals()).encode())
                    return

                if parsed.path in ("/api/logs", "/api/logs/"):
                    try:
                        since = int(parse_qs(parsed.query).get("since", ["0"])[0] or 0)
                    except (ValueError, TypeError):
                        since = 0
                    dash._send(self, "application/json", json.dumps(dash.logs_since(since)).encode())
                    return

                body = b"not found"
                self.send_response(404)
                self.send_header("Content-Type", "text/plain")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, fmt, *args):
                pass

        return Handler

    def start(self):
        server = ThreadingHTTPServer((self.host, self.port), self._handler_class())
        server.daemon_threads = True
        thread = threading.Thread(target=server.serve_forever, daemon=True, name="dashboard")
        thread.start()
        self._cpu_percent()
        self.log(f"dashboard on http://{self.host}:{self.port}")
        return server


_default = None


def init(host="127.0.0.1", port=DASHBOARD_PORT):
    global _default
    _default = Dashboard(host, port)
    return _default


def log(message):
    if _default is not None:
        _default.log(message)
    else:
        print(message, flush=True)


def set_status(**kwargs):
    if _default is not None:
        _default.set_status(**kwargs)


def start():
    if _default is not None:
        return _default.start()
    return None
