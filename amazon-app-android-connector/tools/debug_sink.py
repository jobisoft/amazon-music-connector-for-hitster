#!/usr/bin/env python3
"""Receives the debug log from the phone and keeps it in a file.

  python3 debug_sink.py [port]     (default 8781)

  POST /log   body {"t": <ms>, "tag": "...", "msg": "...", "data": {...}}   one event
              or a JSON list of such events
  GET  /log   the last 300 events as JSON
  GET  /ping  "ok"

The debug app finds this sink by itself: it sends the UDP broadcast "HITSTER-SINK?" to
port 8782 and the sink answers "HITSTER-SINK <http port>"
to the sender. Allow UDP 8782 in the firewall of the PC if the app does not find it.

Everything is also appended to debug.log next to this script. Plain HTTP on the
local network, no token, for debugging only. Stop it when the test is over.
"""
import json
import os
import socket
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
LOG = os.path.join(HERE, "debug.log")
EVENTS = []


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def _send(self, status, body, ctype="application/json"):
        data = body.encode() if isinstance(body, str) else body
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == "/ping":
            return self._send(200, "ok", "text/plain")
        if self.path == "/log":
            return self._send(200, json.dumps(EVENTS[-300:], ensure_ascii=False))
        self._send(404, "{}")

    def do_POST(self):
        if self.path != "/log":
            return self._send(404, "{}")
        size = int(self.headers.get("Content-Length") or 0)
        if size > 200000:
            return self._send(413, "{}")
        try:
            body = json.loads(self.rfile.read(size) or b"null")
        except ValueError:
            return self._send(400, "{}")
        items = body if isinstance(body, list) else [body]
        with open(LOG, "a", encoding="utf-8") as f:
            for item in items:
                if not isinstance(item, dict):
                    continue
                item["from"] = self.client_address[0]
                item["at"] = time.strftime("%H:%M:%S")
                EVENTS.append(item)
                line = json.dumps(item, ensure_ascii=False)
                f.write(line + "\n")
                print(line, flush=True)
        self._send(200, '{"ok": true}')


DISCOVERY_PORT = 8782
DISCOVERY_REQUEST = b"HITSTER-SINK?"


def answer_discovery(http_port, udp_port=DISCOVERY_PORT, sock=None):
    """Answers the broadcast of the app with the HTTP port of this sink (runs until the process ends)."""
    if sock is None:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind(("", udp_port))
    while True:
        data, sender = sock.recvfrom(64)
        if data.strip() == DISCOVERY_REQUEST:
            sock.sendto(("HITSTER-SINK %d" % http_port).encode(), sender)


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8781
    threading.Thread(target=answer_discovery, args=(port,), daemon=True).start()
    print("Debug sink on port %d, finds the app on UDP %d, log file %s" % (port, DISCOVERY_PORT, LOG), flush=True)
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
