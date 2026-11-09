#!/usr/bin/env python3
"""
Native messaging host for the "Amazon Music Connector for Hitster" add-on.

The host has two faces:

  1. stdin/stdout: Mozilla native messaging, talking to the Firefox add-on.
     Each message is preceded by a 4-byte unsigned int (native byte order)
     that gives the byte length of the following UTF-8 JSON payload.

  2. A small HTTP server on the local network, talking to the Android app
     (and, on this computer only, to a developer).

Finding the computer
--------------------
The phone sends the UDP broadcast "AMC-FIND?" to port 8765 (UDP, config key
"discoveryPort"). Every helper answers the sender with the JSON of GET /ping
plus "port", the HTTP port. The address of the sender of the answer is the address
of the computer.

HTTP API
--------
Open (no token):
  GET  /ping       -> {"app": "amazon-music-connector-for-hitster", "version": "...",
                       "name": <computer name>, "id": <id of this installation>,
                       "open": <true if no token is needed>}
  POST /pair       body {"name": <device name>}  ask to be allowed. The user
                   approves on the options page of the add-on. -> {"ok": true, "id": ...}
  GET  /pair/<id>  -> {"state": "pending"|"approved"|"denied"|"expired",
                       "token"?: ..., "name": <computer name>}
                   The id is a secret that only the asking device knows.
  GET  /  /app     -> landing page to install the Android app
  GET  /app/Amazon-Music-Desktop-Connector-for-Hitster-<version>.apk  -> the Android app, if the add-on has bundled one.
                   The address carries the version, so that a phone browser does not
                   mistake a new build for the file it downloaded before.
  GET  /app.apk    -> the same, under an address that never changes

Phone (header "Authorization: Bearer <token>", or no header at all while the
user has allowed open access on the options page, for a trusted network):
  GET  /status     -> {"ok": true, "addon": true}
  POST /play       body {"title", "artist", "year"?, "card"?, "start"? (seconds, default 20)}
  POST /control    body {"action": "back10"|"forward10"|"restart"|"stop"|"seek"|"state", "position"?}
                   Moves the playhead of the song that plays ("restart" jumps to 20 s and plays,
                   "seek" to "position" seconds). "state" only reads. All answer with
                   "position", "duration" (seconds) and "playing".
                   -> {"ok": bool, "message"?: str, "error"?: str}

Debug (header "Authorization: Bearer <adminToken>", and only from 127.0.0.1):
  GET  /info                 everything the options page shows, plus config
  GET  /log?since=R&wait=S   log entries newer than revision R, waits up to S
                             seconds (max 30) if there are none yet
  GET  /tabs                 the open Amazon Music tabs
  POST /pairing              body {"id", "allow"}  decide a pairing like the options page
  POST /dom                  body {"selector"?, "mode"?, "maxBytes"?, "tabId"?,
                                   "waitFor"?, "timeout"?}
                             the DOM of an Amazon Music tab (see content script)
  POST /act                  body {"action": "click"|"type"|"press", "selector",
                                   "index"?, "text"?, "key"?, "timeout"?, "tabId"?}
                             click, type into or press a key on an element of the tab
  POST /reload               reload the add-on (it re-reads its files, the helper
                             process restarts with it)
  POST /navigate             body {"url", "tabId"?}  load an Amazon Music page
                             in the tab and wait until it has loaded
  POST /play                 same as for the phone, logged with source "admin"

The debug endpoints refuse every request that is not from the loopback
interface, even with a valid admin token. The DOM of a logged-in Amazon page
may hold personal data, it must never leave this computer.

Messages add-on -> host:
  { "requestId", "cmd": "getInfo" }
  { "requestId", "cmd": "setConfig", "port"?: int, "regenerateToken"?: bool,
                 "regenerateAdminToken"?: bool, "openAccess"?: bool }
  { "requestId", "cmd": "apkChunk", "offset", "data" (base64), "total",
                 "sha256", "version", "last" }
  { "requestId", "cmd": "pairDecide", "id": str, "allow": bool }
  { "requestId", "cmd": "result", "result": { ... } }   answer to a forwarded request
  {              "cmd": "event", "level": str, "text": str }

Messages host -> add-on:
  { "requestId", "ok": true, "result": any }            reply to a command
  { "requestId", "ok": false, "error": str }
  { "requestId", "type": "play" | "dom" | "tabs" | "navigate" | "act" | "reload", ... } forwarded request
  { "event": "pairRequest", "id", "name", "remote" }     a phone asks to be allowed
"""

import base64
import hashlib
import hmac
import json
import os
import platform
import re
import secrets
import socket
import struct
import sys
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

HOST_VERSION = "1.0.0"
APP_ID = "amazon-music-connector-for-hitster"
DEFAULT_PORT = 8765
DEFAULT_DISCOVERY_PORT = 8765  # UDP, a different namespace than the HTTP port
DISCOVERY_REQUEST = b"AMC-FIND?"
MAX_BODY = 4096
MAX_FIELD = 300
PLAY_TIMEOUT = 40.0
CONTROL_ACTIONS = ("back10", "forward10", "restart", "stop", "seek", "state")
CONTROL_TIMEOUT = 30.0  # a search in a slow web player, and requests wait in a queue
DOM_TIMEOUT = 25.0
MAX_LOG = 300
MAX_APK = 200 * 1024 * 1024
MAX_WAIT = 30.0

# ── Configuration ──────────────────────────────────────────────────────────────


def config_dir():
    override = os.environ.get("AMC_CONFIG_DIR")
    if override:
        return override
    if os.name == "nt":
        base = os.environ.get("APPDATA") or os.path.expanduser("~")
    elif sys.platform == "darwin":
        base = os.path.expanduser("~/Library/Application Support")
    else:
        base = os.environ.get("XDG_CONFIG_HOME") or os.path.expanduser("~/.config")
    return os.path.join(base, APP_ID)


CONFIG_DIR = config_dir()
CONFIG_PATH = os.path.join(CONFIG_DIR, "config.json")
APK_PATH = os.path.join(CONFIG_DIR, "app.apk")
APK_META_PATH = os.path.join(CONFIG_DIR, "app.json")


def load_config():
    cfg = {}
    try:
        with open(CONFIG_PATH, "r", encoding="utf-8") as f:
            cfg = json.load(f)
    except (OSError, ValueError):
        pass
    if not isinstance(cfg, dict):
        cfg = {}
    port = cfg.get("port")
    if not isinstance(port, int) or not 1024 <= port <= 65535:
        cfg["port"] = DEFAULT_PORT
    for key in ("token", "adminToken"):
        if not isinstance(cfg.get(key), str) or len(cfg[key]) < 16:
            cfg[key] = secrets.token_urlsafe(18)
    if not isinstance(cfg.get("openAccess"), bool):
        cfg["openAccess"] = False  # a token is needed, unless the user says the network is trusted
    if not isinstance(cfg.get("instanceId"), str) or len(cfg["instanceId"]) < 16:
        cfg["instanceId"] = uuid.uuid4().hex  # lets the phone find this computer again after an address change
    save_config(cfg)
    return cfg


def save_config(cfg):
    os.makedirs(CONFIG_DIR, exist_ok=True)
    tmp = CONFIG_PATH + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(cfg, f, indent=2)
    try:
        os.chmod(tmp, 0o600)
    except OSError:
        pass
    os.replace(tmp, CONFIG_PATH)


def load_apk_meta():
    try:
        with open(APK_META_PATH, "r", encoding="utf-8") as f:
            meta = json.load(f)
        if os.path.getsize(APK_PATH) == meta.get("size"):
            return meta
    except (OSError, ValueError, AttributeError):
        pass
    return None


# ── Shared state ───────────────────────────────────────────────────────────────

STARTED = time.time()
STATE_LOCK = threading.Lock()
LOG_COND = threading.Condition(STATE_LOCK)  # shares STATE_LOCK
CONFIG = load_config()
APK_META = load_apk_meta()
LOG = []  # newest last, see log_add()
SEQ = 0
REV = 0
TOTAL_REQUESTS = 0
PENDING = {}  # requestId -> {"event": Event, "result": dict|None}
SERVER = {"httpd": None, "thread": None, "error": None, "discoverySocket": None, "discoveryError": None}
STDOUT_LOCK = threading.Lock()
APK_LOCK = threading.Lock()
APK_UPLOAD = {"fh": None, "hash": None, "written": 0}


PAIRINGS = {}  # id -> {"id", "name", "remote", "created", "state", "decided"}
PAIR_TTL = 120.0
PAIR_MAX_PENDING = 8
PAIR_RATE = {"window": 0.0, "count": 0}


def pair_expire():
    """Forget old requests. Call with STATE_LOCK held."""
    now = time.time()
    for pid in list(PAIRINGS):
        p = PAIRINGS[pid]
        if p["state"] == "pending" and now - p["created"] > PAIR_TTL:
            p["state"] = "expired"
            p["decided"] = now
        if p["state"] != "pending" and now - p["decided"] > PAIR_TTL:
            del PAIRINGS[pid]


def pair_create(name, remote):
    with STATE_LOCK:
        pair_expire()
        now = time.time()
        if now - PAIR_RATE["window"] > 60:
            PAIR_RATE.update(window=now, count=0)
        PAIR_RATE["count"] += 1
        pending = [p for p in PAIRINGS.values() if p["state"] == "pending"]
        if PAIR_RATE["count"] > 10 or len(pending) >= PAIR_MAX_PENDING:
            raise ValueError("Too many pairing requests, try again in a minute.")
        for p in pending:  # the same phone asking again gets the same request
            if p["remote"] == remote and p["name"] == name:
                return p["id"], False
        pid = secrets.token_urlsafe(16)
        PAIRINGS[pid] = {"id": pid, "name": name, "remote": remote, "created": now,
                         "state": "pending", "decided": 0.0}
        return pid, True


def pair_get(pid):
    with STATE_LOCK:
        pair_expire()
        p = PAIRINGS.get(pid)
        if p is None:
            return {"state": "expired"}
        out = {"state": p["state"]}
        if p["state"] == "approved":
            out["token"] = CONFIG["token"]
        return out


def pair_decide(pid, allow):
    with STATE_LOCK:
        pair_expire()
        p = PAIRINGS.get(pid)
        if p is None or p["state"] != "pending":
            raise ValueError("This request is gone or has been decided already.")
        p["state"] = "approved" if allow else "denied"
        p["decided"] = time.time()
        name = p["name"]
    log_add({"kind": "event", "level": "info",
             "text": "%s %s." % (name, "was allowed to connect" if allow else "was refused")})


def pair_pending():
    with STATE_LOCK:
        pair_expire()
        now = time.time()
        return [{"id": p["id"], "name": p["name"], "remote": p["remote"],
                 "age": int(now - p["created"])}
                for p in PAIRINGS.values() if p["state"] == "pending"]


def log_add(entry):
    """Append a log entry. Every create and every update gets a new revision,
    so a monitoring client asks for "everything newer than revision R"."""
    global SEQ, REV
    with LOG_COND:
        SEQ += 1
        REV += 1
        entry.update(seq=SEQ, rev=REV, time=time.time())
        LOG.append(entry)
        del LOG[:-MAX_LOG]
        LOG_COND.notify_all()
    return entry


def log_update(entry, **fields):
    global REV
    with LOG_COND:
        REV += 1
        entry.update(fields)
        entry["rev"] = REV
        LOG_COND.notify_all()


def log_since(since, wait):
    with LOG_COND:
        if wait > 0:
            LOG_COND.wait_for(lambda: REV > since, timeout=min(wait, MAX_WAIT))
        return [dict(e) for e in LOG if e["rev"] > since], REV


# ── Native messaging I/O ───────────────────────────────────────────────────────


def read_message():
    raw_len = sys.stdin.buffer.read(4)
    if len(raw_len) < 4:
        return None
    (length,) = struct.unpack("=I", raw_len)
    data = sys.stdin.buffer.read(length)
    if len(data) < length:
        return None
    return json.loads(data.decode("utf-8"))


def send_message(obj):
    data = json.dumps(obj, ensure_ascii=False).encode("utf-8")
    with STDOUT_LOCK:
        sys.stdout.buffer.write(struct.pack("=I", len(data)))
        sys.stdout.buffer.write(data)
        sys.stdout.buffer.flush()


# ── Helpers ────────────────────────────────────────────────────────────────────


def local_addresses():
    """Best effort list of the IPv4 addresses of this machine. The first one
    is the address of the interface that has the default route."""
    found = []
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))  # no packet is sent for UDP connect
        found.append(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            found.append(info[4][0])
    except OSError:
        pass
    result = []
    for ip in found:
        if ip not in result and not ip.startswith("127."):
            result.append(ip)
    return result


def clean_text(value):
    if not isinstance(value, str):
        return None
    value = " ".join(value.split())
    if not value or len(value) > MAX_FIELD:
        return None
    return value


def forward(request_type, payload, timeout):
    """Send a request to the add-on and wait for its answer (a dict)."""
    request_id = uuid.uuid4().hex
    entry = {"event": threading.Event(), "result": None}
    with STATE_LOCK:
        PENDING[request_id] = entry
    try:
        send_message({"requestId": request_id, "type": request_type, **payload})
    except (OSError, ValueError) as e:
        with STATE_LOCK:
            PENDING.pop(request_id, None)
        return {"ok": False, "error": "Could not reach the add-on: %s" % e}
    if not entry["event"].wait(timeout):
        with STATE_LOCK:
            PENDING.pop(request_id, None)
        return {"ok": False, "error": "The add-on did not answer in time."}
    with STATE_LOCK:
        PENDING.pop(request_id, None)
    return entry["result"] or {"ok": False, "error": "Empty answer from the add-on."}


def apk_filename(meta):
    """Amazon-Music-Desktop-Connector-for-Hitster-<version>.apk, with a version that is safe in a file name and an address."""
    version = re.sub(r"[^A-Za-z0-9._-]", "_", str((meta or {}).get("version", "")))[:40]
    return "Amazon-Music-Desktop-Connector-for-Hitster-%s.apk" % version if version else "Amazon-Music-Desktop-Connector-for-Hitster.apk"


def apk_info():
    with STATE_LOCK:
        meta = APK_META
    if meta is None:
        return {"present": False}
    return {"present": True, "size": meta["size"], "sha256": meta["sha256"],
            "version": meta.get("version", "")}


# ── Landing page for the Android app ───────────────────────────────────────────

PAGE_EN = {
    "lang": "en",
    "title": "Hitster Amazon Desktop Connector (Android)",
    "intro": "This app scans Hitster cards and plays the songs on Amazon Music in the browser of your computer.",
    "download": "Download the app",
    "missing": "The add-on has no Android app bundled yet.",
    "steps": [
        "Tap the button to download the file.",
        "Open the downloaded file. Android asks whether your browser may install apps. Allow it for this one time.",
        "Open the app, go to Connector Config and enter the server address and the token from the options page of the Firefox add-on.",
    ],
}
PAGE_DE = {
    "lang": "de",
    "title": "Hitster Amazon Desktop Connector (Android)",
    "intro": "Die App scannt Hitster-Karten und spielt die Songs über Amazon Music im Browser deines Computers ab.",
    "download": "App herunterladen",
    "missing": "Im Add-on ist noch keine Android-App enthalten.",
    "steps": [
        "Tippe auf den Knopf, um die Datei herunterzuladen.",
        "Öffne die heruntergeladene Datei. Android fragt, ob dein Browser Apps installieren darf. Erlaube es dieses eine Mal.",
        "Öffne die App, gehe zu Connector Config und trage Serveradresse und Token von der Optionsseite des Firefox-Add-ons ein.",
    ],
}


def landing_page(accept_language):
    text = PAGE_DE if accept_language.lower().startswith("de") else PAGE_EN
    meta = apk_info()
    if meta["present"]:
        version = meta.get("version") or ""
        size_mb = meta["size"] / (1024 * 1024)
        button = ('<p><a class="btn" href="/app/%s">%s</a></p><p class="small">%s %s, %.1f MB</p>'
                  % (apk_filename(meta), text["download"], "Version", version, size_mb))
    else:
        button = '<p class="warn">%s</p>' % text["missing"]
    steps = "".join("<li>%s</li>" % s for s in text["steps"])
    return ("""<!DOCTYPE html>
<html lang="%(lang)s"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>%(title)s</title>
<style>
body{font-family:system-ui,sans-serif;margin:0;padding:24px;max-width:480px;margin:auto;color:#1e293b}
h1{font-size:22px}.btn{display:block;text-align:center;background:#0ea5e9;color:#fff;
padding:16px;border-radius:10px;text-decoration:none;font-size:18px;font-weight:600}
.small{color:#64748b;font-size:13px;text-align:center}.warn{color:#b91c1c}li{margin-bottom:10px;line-height:1.5}
</style></head><body><h1>%(title)s</h1><p>%(intro)s</p>%(button)s<ol>%(steps)s</ol></body></html>
""" % {"lang": text["lang"], "title": text["title"], "intro": text["intro"],
       "button": button, "steps": steps}).encode("utf-8")


# ── HTTP server ────────────────────────────────────────────────────────────────


def is_loopback(address):
    return address in ("127.0.0.1", "::1") or address.startswith("::ffff:127.")


class Handler(BaseHTTPRequestHandler):
    server_version = "AmazonMusicConnector/" + HOST_VERSION
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass  # stdout belongs to native messaging, never print to it

    # -- replies --------------------------------------------------------------

    def _reply(self, status, obj):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self._send(status, "application/json; charset=utf-8", body)

    def _send(self, status, content_type, body, extra_headers=None):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        for key, value in (extra_headers or {}).items():
            self.send_header(key, value)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    # -- authorization --------------------------------------------------------

    def _role(self):
        header = self.headers.get("Authorization", "")
        if not header.startswith("Bearer "):
            return None
        given = header[7:].strip().encode()
        with STATE_LOCK:
            admin = CONFIG["adminToken"].encode()
            user = CONFIG["token"].encode()
        if hmac.compare_digest(given, admin):
            return "admin"
        if hmac.compare_digest(given, user):
            return "user"
        return None

    def _phone_role(self):
        """The role for the endpoints of the phone. With open access, a missing or wrong
        token does not matter. The debug endpoints never use this."""
        role = self._role()
        if role is None:
            with STATE_LOCK:
                if CONFIG["openAccess"]:
                    return "user"
        return role

    def _require(self, admin):
        """Return True if the request may go on. Otherwise the reply is sent."""
        role = self._role() if admin else self._phone_role()
        if role is None:
            self._reply(401, {"ok": False, "error": "Invalid token."})
            return False
        if admin and (role != "admin" or not is_loopback(self.client_address[0])):
            self._reply(403, {"ok": False, "error": "Debug access is only for the local computer."})
            return False
        return True

    def _log_foreign(self, path):
        """Write every request of another device into the log, so a developer can see
        whether the phone reaches this computer at all. Requests from this
        computer are left out, they are the developer's own."""
        if is_loopback(self.client_address[0]) or path == "/log":
            return
        if path.startswith("/pair/"):
            path = "/pair/..."  # the id is a secret
        log_add({"kind": "http", "remote": self.client_address[0], "method": self.command,
                 "path": path, "agent": self.headers.get("User-Agent", "")[:120]})

    # -- GET ------------------------------------------------------------------

    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        url = urlparse(self.path)
        path = url.path
        self._log_foreign(path)
        if path == "/ping":
            return self._reply(200, ping_info())
        if path.startswith("/pair/"):
            result = pair_get(path[len("/pair/"):])
            result["name"] = socket.gethostname()
            return self._reply(200, result)
        if path in ("/", "/app"):
            return self._send(200, "text/html; charset=utf-8",
                              landing_page(self.headers.get("Accept-Language", "")))
        if path == "/app.apk" or (path.startswith("/app/") and path.endswith(".apk")):
            return self._send_apk()
        if path == "/status":
            if not self._require(admin=False):
                return
            return self._reply(200, {"ok": True, "addon": True})
        if path == "/info":
            if not self._require(admin=True):
                return
            return self._reply(200, get_info(debug=True))
        if path == "/log":
            if not self._require(admin=True):
                return
            query = parse_qs(url.query)
            try:
                since = int(query.get("since", ["0"])[0])
                wait = float(query.get("wait", ["0"])[0])
            except ValueError:
                return self._reply(400, {"ok": False, "error": "since and wait must be numbers."})
            entries, rev = log_since(since, wait)
            return self._reply(200, {"ok": True, "rev": rev, "entries": entries})
        if path == "/tabs":
            if not self._require(admin=True):
                return
            result = forward("tabs", {}, PLAY_TIMEOUT)
            return self._reply(200 if result.get("ok") else 502, result)
        self._reply(404, {"ok": False, "error": "Not found."})

    def _send_apk(self):
        with STATE_LOCK:
            meta = APK_META
        if meta is None:
            return self._reply(404, {"ok": False, "error": "No Android app is bundled."})
        try:
            size = os.path.getsize(APK_PATH)
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.android.package-archive")
            self.send_header("Content-Length", str(size))
            self.send_header("Content-Disposition", 'attachment; filename="%s"' % apk_filename(meta))
            self.end_headers()
            if self.command == "HEAD":
                return
            with open(APK_PATH, "rb") as f:
                while True:
                    chunk = f.read(65536)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
        except OSError:
            self.close_connection = True

    # -- POST -----------------------------------------------------------------

    def _read_json(self):
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            length = -1
        if length < 0 or length > MAX_BODY:
            self._reply(413, {"ok": False, "error": "Bad request size."})
            return None
        try:
            data = json.loads(self.rfile.read(length).decode("utf-8") or "{}")
        except (ValueError, UnicodeDecodeError):
            self._reply(400, {"ok": False, "error": "Body is not valid JSON."})
            return None
        if not isinstance(data, dict):
            self._reply(400, {"ok": False, "error": "Body must be a JSON object."})
            return None
        return data

    def do_POST(self):
        path = urlparse(self.path).path
        if path == "/play":
            return self._post_play()
        if path == "/control":
            return self._post_control()
        if path == "/autoplay":
            return self._post_autoplay()
        if path == "/dom":
            return self._post_dom()
        if path == "/navigate":
            return self._post_navigate()
        if path == "/act":
            return self._post_act()
        if path == "/reload":
            return self._post_reload()
        if path == "/pair":
            return self._post_pair()
        if path == "/pairing":
            return self._post_pairing()
        self._reply(404, {"ok": False, "error": "Not found."})

    def _post_play(self):
        global TOTAL_REQUESTS
        role = self._phone_role()
        remote = self.client_address[0]
        if role is None:
            log_add({"kind": "request", "remote": remote, "source": "unknown", "title": "",
                     "artist": "", "status": "unauthorized", "detail": "Invalid or missing token."})
            return self._reply(401, {"ok": False, "error": "Invalid token."})
        data = self._read_json()
        if data is None:
            return
        title = clean_text(data.get("title"))
        artist = clean_text(data.get("artist"))
        if not title or not artist:
            return self._reply(400, {"ok": False, "error": "Fields title and artist are required."})
        payload = {"title": title, "artist": artist}
        year = data.get("year")
        if isinstance(year, int) and 1000 <= year <= 3000:
            payload["year"] = year
        card = clean_text(data.get("card"))
        if card:
            payload["card"] = card
        start = data.get("start")
        if isinstance(start, (int, float)) and not isinstance(start, bool) and 0 <= start <= 3600:
            payload["start"] = float(start)  # seconds into the song, the add-on starts at 20 by default

        source = "admin" if role == "admin" else "phone"
        with STATE_LOCK:
            TOTAL_REQUESTS += 1
        entry = log_add({"kind": "request", "remote": remote, "source": source, "title": title,
                         "artist": artist, "year": payload.get("year"), "card": card,
                         "status": "pending", "detail": ""})
        started = time.time()
        result = forward("play", payload, PLAY_TIMEOUT)
        log_update(entry, status="ok" if result.get("ok") else "error",
                   detail=result.get("message") or result.get("error") or "",
                   ms=int((time.time() - started) * 1000))
        self._reply(200 if result.get("ok") else 502, result)

    def _post_control(self):
        role = self._phone_role()
        if role is None:
            return self._reply(401, {"ok": False, "error": "Invalid token."})
        data = self._read_json()
        if data is None:
            return
        action = data.get("action")
        if action not in CONTROL_ACTIONS:
            return self._reply(400, {"ok": False, "error": "action must be one of: " + ", ".join(CONTROL_ACTIONS) + "."})
        payload = {"action": action}
        if action == "seek":
            position = data.get("position")
            if isinstance(position, bool) or not isinstance(position, (int, float)) or not 0 <= position <= 86400:
                return self._reply(400, {"ok": False, "error": "position must be a number of seconds."})
            payload["position"] = float(position)
        if action == "state":
            # the phone asks every second, that does not belong into the log
            result = forward("control", payload, CONTROL_TIMEOUT)
            return self._reply(200 if result.get("ok") else 502, result)
        entry = log_add({"kind": "request", "remote": self.client_address[0],
                         "source": "admin" if role == "admin" else "phone", "title": "Control " + action,
                         "artist": "", "status": "pending", "detail": ""})
        started = time.time()
        result = forward("control", payload, CONTROL_TIMEOUT)
        log_update(entry, status="ok" if result.get("ok") else "error",
                   detail=result.get("message") or result.get("error") or "",
                   ms=int((time.time() - started) * 1000))
        self._reply(200 if result.get("ok") else 502, result)

    def _post_autoplay(self):
        if not self._require(admin=True):
            return
        result = forward("autoplay", {}, 30.0)
        self._reply(200 if result.get("ok") else 502, result)

    def _post_dom(self):
        if not self._require(admin=True):
            return
        data = self._read_json()
        if data is None:
            return
        payload = {}
        selector = data.get("selector")
        if isinstance(selector, str) and selector.strip():
            payload["selector"] = selector.strip()[:MAX_FIELD]
        mode = data.get("mode")
        if mode is not None:
            if mode not in ("html", "text", "summary"):
                return self._reply(400, {"ok": False, "error": "mode must be html, text or summary."})
            payload["mode"] = mode
        wait_for = data.get("waitFor")
        if isinstance(wait_for, str) and wait_for.strip():
            payload["waitFor"] = wait_for.strip()[:MAX_FIELD]
        for key in ("maxBytes", "tabId", "limit", "timeout"):
            value = data.get(key)
            if isinstance(value, int) and value >= 0:
                payload[key] = value
        if isinstance(data.get("strip"), bool):
            payload["strip"] = data["strip"]
        entry = log_add({"kind": "debug", "remote": self.client_address[0], "source": "admin",
                         "title": "DOM request", "artist": "", "status": "pending",
                         "detail": json.dumps(payload)})
        started = time.time()
        result = forward("dom", payload, DOM_TIMEOUT + 30)
        size = len(json.dumps(result.get("data", ""))) if result.get("ok") else 0
        log_update(entry, status="ok" if result.get("ok") else "error",
                   detail=("%d bytes" % size) if result.get("ok") else result.get("error", ""),
                   ms=int((time.time() - started) * 1000))
        self._reply(200 if result.get("ok") else 502, result)


    def _post_act(self):
        if not self._require(admin=True):
            return
        data = self._read_json()
        if data is None:
            return
        action = data.get("action")
        selector = data.get("selector")
        if action not in ("click", "type", "press"):
            return self._reply(400, {"ok": False, "error": "action must be click, type or press."})
        if not isinstance(selector, str) or not selector.strip():
            return self._reply(400, {"ok": False, "error": "selector is required."})
        payload = {"action": action, "selector": selector.strip()[:MAX_FIELD]}
        text = data.get("text")
        if text is not None:
            if not isinstance(text, str) or len(text) > MAX_FIELD:
                return self._reply(400, {"ok": False, "error": "text must be a short string."})
            payload["text"] = text
        key = data.get("key")
        if key is not None:
            if not isinstance(key, str) or len(key) > 20:
                return self._reply(400, {"ok": False, "error": "key must be a short string."})
            payload["key"] = key
        for name in ("index", "tabId", "timeout"):
            if isinstance(data.get(name), int) and data[name] >= 0:
                payload[name] = data[name]
        entry = log_add({"kind": "debug", "remote": self.client_address[0], "source": "admin",
                         "title": "Act " + action, "artist": "", "status": "pending",
                         "detail": json.dumps(payload, ensure_ascii=False)})
        started = time.time()
        result = forward("act", payload, 45.0)
        log_update(entry, status="ok" if result.get("ok") else "error",
                   detail=json.dumps(payload, ensure_ascii=False) if result.get("ok") else result.get("error", ""),
                   ms=int((time.time() - started) * 1000))
        self._reply(200 if result.get("ok") else 502, result)

    def _post_pair(self):
        data = self._read_json()
        if data is None:
            return
        name = clean_text(data.get("name")) or "Phone"
        try:
            pid, is_new = pair_create(name[:40], self.client_address[0])
        except ValueError as e:
            return self._reply(429, {"ok": False, "error": str(e)})
        if is_new:
            log_add({"kind": "event", "level": "info",
                     "text": "%s (%s) asks to connect." % (name, self.client_address[0])})
            try:
                send_message({"event": "pairRequest", "id": pid, "name": name[:40],
                              "remote": self.client_address[0]})
            except (OSError, ValueError):
                pass
        self._reply(200, {"ok": True, "id": pid})

    def _post_pairing(self):
        if not self._require(admin=True):
            return
        data = self._read_json()
        if data is None:
            return
        try:
            pair_decide(str(data.get("id", "")), bool(data.get("allow")))
        except ValueError as e:
            return self._reply(404, {"ok": False, "error": str(e)})
        self._reply(200, {"ok": True})

    def _post_reload(self):
        if not self._require(admin=True):
            return
        log_add({"kind": "debug", "remote": self.client_address[0], "source": "admin",
                 "title": "Reload add-on", "artist": "", "status": "ok", "detail": ""})
        result = forward("reload", {}, 10.0)
        self._reply(200 if result.get("ok") else 502, result)

    def _post_navigate(self):
        if not self._require(admin=True):
            return
        data = self._read_json()
        if data is None:
            return
        url = data.get("url")
        if not isinstance(url, str) or not re.match(r"^https://music\.amazon\.[a-z.]+/", url):
            return self._reply(400, {"ok": False, "error": "url must be an https://music.amazon.* page."})
        payload = {"url": url[:2000]}
        if isinstance(data.get("tabId"), int):
            payload["tabId"] = data["tabId"]
        entry = log_add({"kind": "debug", "remote": self.client_address[0], "source": "admin",
                         "title": "Navigate", "artist": "", "status": "pending", "detail": url})
        started = time.time()
        result = forward("navigate", payload, 40.0)
        log_update(entry, status="ok" if result.get("ok") else "error",
                   detail=url if result.get("ok") else result.get("error", ""),
                   ms=int((time.time() - started) * 1000))
        self._reply(200 if result.get("ok") else 502, result)


def stop_server():
    httpd = SERVER["httpd"]
    if httpd is not None:
        try:
            httpd.shutdown()
            httpd.server_close()
        except OSError:
            pass
    SERVER["httpd"] = None
    SERVER["thread"] = None


def ping_info():
    """What /ping says, and what the helper answers to the broadcast of the phone."""
    return {"app": APP_ID, "version": HOST_VERSION, "name": socket.gethostname(),
            "id": CONFIG["instanceId"], "open": CONFIG["openAccess"]}


def discovery_answer(request):
    """The reply to a UDP datagram of the phone, or None if it is not a request for this helper."""
    if request.strip() != DISCOVERY_REQUEST:
        return None
    with STATE_LOCK:
        info = ping_info()
        info["port"] = CONFIG["port"]
    return json.dumps(info).encode()


def discovery_loop(sock):
    while True:
        try:
            data, sender = sock.recvfrom(64)
        except OSError:
            return  # the socket was closed
        answer = discovery_answer(data)
        if answer is not None:
            try:
                sock.sendto(answer, sender)
            except OSError:
                pass


def start_discovery():
    """Listens for the broadcast of the phone ("AMC-FIND?") and tells it the address of the HTTP server."""
    SERVER["discoveryError"] = None
    with STATE_LOCK:
        port = CONFIG.get("discoveryPort", DEFAULT_DISCOVERY_PORT)
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.bind(("", port))
    except OSError as e:
        SERVER["discoveryError"] = "Cannot listen for the search of the phone on UDP port %d: %s" % (port, e)
        return
    SERVER["discoverySocket"] = sock
    threading.Thread(target=discovery_loop, args=(sock,), name="discovery", daemon=True).start()


def stop_discovery():
    sock = SERVER.get("discoverySocket")
    SERVER["discoverySocket"] = None
    if sock is not None:
        try:
            sock.close()
        except OSError:
            pass


def start_server():
    stop_server()
    SERVER["error"] = None
    with STATE_LOCK:
        port = CONFIG["port"]
    try:
        httpd = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    except OSError as e:
        SERVER["error"] = "Cannot listen on port %d: %s" % (port, e)
        return
    httpd.daemon_threads = True
    thread = threading.Thread(target=httpd.serve_forever, name="http", daemon=True)
    thread.start()
    SERVER["httpd"] = httpd
    SERVER["thread"] = thread


# ── Commands from the add-on ───────────────────────────────────────────────────


def get_info(debug=False):
    with STATE_LOCK:
        cfg = dict(CONFIG)
        requests = [dict(e) for e in LOG if e.get("kind") == "request"][-30:]
        total = TOTAL_REQUESTS
        rev = REV
    info = {
        "hostVersion": HOST_VERSION,
        "pythonVersion": platform.python_version(),
        "platform": "%s %s" % (platform.system(), platform.release()),
        "hostname": socket.gethostname(),
        "pid": os.getpid(),
        "startedAt": STARTED,
        "uptime": int(time.time() - STARTED),
        "configPath": CONFIG_PATH,
        "port": cfg["port"],
        "token": cfg["token"],
        "serverRunning": SERVER["httpd"] is not None,
        "serverError": SERVER["error"],
        "discoveryError": SERVER.get("discoveryError"),
        "addresses": local_addresses(),
        "totalRequests": total,
        "requests": requests,
        "rev": rev,
        "apk": apk_info(),
        "pairing": pair_pending(),
        "instanceId": cfg["instanceId"],
        "openAccess": cfg["openAccess"],
    }
    if debug:
        # The admin token stays in the config file. Never echo it.
        del info["token"]
    return info


def set_config(msg):
    changed_port = False
    with STATE_LOCK:
        port = msg.get("port")
        if port is not None:
            if not isinstance(port, int) or not 1024 <= port <= 65535:
                raise ValueError("The port must be a number between 1024 and 65535.")
            changed_port = port != CONFIG["port"]
            CONFIG["port"] = port
        if msg.get("regenerateToken"):
            CONFIG["token"] = secrets.token_urlsafe(18)
        if msg.get("regenerateAdminToken"):
            CONFIG["adminToken"] = secrets.token_urlsafe(18)
        if isinstance(msg.get("openAccess"), bool):
            CONFIG["openAccess"] = msg["openAccess"]
        save_config(CONFIG)
    if changed_port:
        start_server()
    return get_info()


def apk_chunk(msg):
    """Receive the Android app from the add-on, one chunk at a time."""
    global APK_META
    offset = msg.get("offset")
    total = msg.get("total")
    if not isinstance(offset, int) or not isinstance(total, int) or not 0 < total <= MAX_APK:
        raise ValueError("Bad chunk header.")
    data = base64.b64decode(msg.get("data", ""), validate=True)
    with APK_LOCK:
        if offset == 0:
            if APK_UPLOAD["fh"]:
                APK_UPLOAD["fh"].close()
            os.makedirs(CONFIG_DIR, exist_ok=True)
            APK_UPLOAD["fh"] = open(APK_PATH + ".part", "wb")
            APK_UPLOAD["hash"] = hashlib.sha256()
            APK_UPLOAD["written"] = 0
        if APK_UPLOAD["fh"] is None or offset != APK_UPLOAD["written"]:
            raise ValueError("Unexpected chunk offset.")
        if APK_UPLOAD["written"] + len(data) > total:
            raise ValueError("More data than announced.")
        APK_UPLOAD["fh"].write(data)
        APK_UPLOAD["hash"].update(data)
        APK_UPLOAD["written"] += len(data)
        if msg.get("last"):
            APK_UPLOAD["fh"].close()
            APK_UPLOAD["fh"] = None
            digest = APK_UPLOAD["hash"].hexdigest()
            if APK_UPLOAD["written"] != total or digest != msg.get("sha256"):
                os.remove(APK_PATH + ".part")
                raise ValueError("The received file does not match its checksum.")
            os.replace(APK_PATH + ".part", APK_PATH)
            meta = {"size": total, "sha256": digest, "version": str(msg.get("version", ""))}
            with open(APK_META_PATH, "w", encoding="utf-8") as f:
                json.dump(meta, f)
            with STATE_LOCK:
                APK_META = meta
            log_add({"kind": "event", "level": "info",
                     "text": "Android app %s stored (%d bytes)." % (meta["version"], total)})
        return {"written": APK_UPLOAD["written"]}


def handle_command(msg):
    request_id = msg.get("requestId")
    cmd = msg.get("cmd")
    try:
        if cmd == "getInfo":
            result = get_info()
        elif cmd == "setConfig":
            result = set_config(msg)
        elif cmd == "apkChunk":
            result = apk_chunk(msg)
        elif cmd == "pairDecide":
            pair_decide(str(msg.get("id", "")), bool(msg.get("allow")))
            result = get_info()
        elif cmd == "event":
            log_add({"kind": "event", "level": str(msg.get("level", "info"))[:10],
                     "text": str(msg.get("text", ""))[:2000]})
            return
        elif cmd == "result":
            with STATE_LOCK:
                entry = PENDING.get(request_id)
            if entry is None:
                return  # the HTTP side gave up already
            result = msg.get("result")
            entry["result"] = result if isinstance(result, dict) else {"ok": False, "error": "Bad result."}
            entry["event"].set()
            return
        else:
            raise ValueError("Unknown command: %s" % cmd)
        send_message({"requestId": request_id, "ok": True, "result": result})
    except Exception as e:  # report every failure to the add-on, never die silently
        send_message({"requestId": request_id, "ok": False, "error": str(e)})


def main():
    start_server()
    start_discovery()
    log_add({"kind": "event", "level": "info", "text": "Helper app %s started." % HOST_VERSION})
    while True:
        try:
            msg = read_message()
        except (ValueError, OSError):
            break
        if msg is None:
            break  # Firefox closed the pipe
        if isinstance(msg, dict):
            threading.Thread(target=handle_command, args=(msg,), daemon=True).start()
    stop_discovery()
    stop_server()


if __name__ == "__main__":
    main()
