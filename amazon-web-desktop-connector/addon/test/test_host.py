#!/usr/bin/env python3
"""
Tests for the native helper app. They play the role of Firefox (stdin/stdout)
and of the phone (HTTP). Run with:  python3 test/test_host.py
"""
import base64
import hashlib
import json
import os
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request

HOST = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src",
                    "native-messaging-app", "amazon_music_connector_host.py")


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


class HostTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.dir = tempfile.TemporaryDirectory()
        cls.port = free_port()
        cls.udp_port = free_port()
        with open(os.path.join(cls.dir.name, "config.json"), "w") as f:
            json.dump({"port": cls.port, "discoveryPort": cls.udp_port}, f)
        env = dict(os.environ, AMC_CONFIG_DIR=cls.dir.name)
        cls.proc = subprocess.Popen([sys.executable, "-I", HOST], stdin=subprocess.PIPE,
                                    stdout=subprocess.PIPE, env=env)
        cls.inbox = []
        cls.cond = threading.Condition()
        threading.Thread(target=cls._reader, daemon=True).start()
        cls.info = cls.command("getInfo")["result"]
        with open(os.path.join(cls.dir.name, "config.json")) as f:
            cfg = json.load(f)
        cls.token, cls.admin = cfg["token"], cfg["adminToken"]

    @classmethod
    def tearDownClass(cls):
        cls.proc.stdin.close()
        cls.proc.wait(5)
        cls.dir.cleanup()

    # -- the Firefox side --------------------------------------------------

    @classmethod
    def _reader(cls):
        while True:
            raw = cls.proc.stdout.read(4)
            if len(raw) < 4:
                return
            msg = json.loads(cls.proc.stdout.read(struct.unpack("=I", raw)[0]))
            with cls.cond:
                cls.inbox.append(msg)
                cls.cond.notify_all()

    @classmethod
    def send(cls, obj):
        data = json.dumps(obj).encode()
        cls.proc.stdin.write(struct.pack("=I", len(data)) + data)
        cls.proc.stdin.flush()

    @classmethod
    def wait_for(cls, predicate, timeout=5):
        end = time.time() + timeout
        with cls.cond:
            while True:
                for msg in cls.inbox:
                    if predicate(msg):
                        cls.inbox.remove(msg)
                        return msg
                left = end - time.time()
                if left <= 0:
                    raise TimeoutError("no matching message")
                cls.cond.wait(left)

    @classmethod
    def command(cls, cmd, **args):
        rid = "t%d" % time.time_ns()
        cls.send({"requestId": rid, "cmd": cmd, **args})
        return cls.wait_for(lambda m: m.get("requestId") == rid)

    def addon_answers(self, type_, result):
        """Answer the next forwarded request of the given type."""
        def run():
            msg = self.wait_for(lambda m: m.get("type") == type_)
            self.seen = msg
            self.send({"requestId": msg["requestId"], "cmd": "result", "result": result})
        t = threading.Thread(target=run)
        t.start()
        return t

    # -- the HTTP side -----------------------------------------------------

    def http(self, method, path, body=None, token=None, host="127.0.0.1"):
        headers = {"Authorization": "Bearer " + token} if token else {}
        req = urllib.request.Request("http://%s:%d%s" % (host, self.port, path), method=method,
                                     data=json.dumps(body).encode() if body is not None else None,
                                     headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                return r.status, r.read(), r.headers
        except urllib.error.HTTPError as e:
            with e:
                return e.code, e.read(), e.headers

    def jhttp(self, *args, **kw):
        status, body, _ = self.http(*args, **kw)
        return status, json.loads(body)

    # -- tests -------------------------------------------------------------

    def test_phone_finds_the_helper_by_broadcast_request(self):
        client = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        client.settimeout(3)
        try:
            client.sendto(b"something else", ("127.0.0.1", self.udp_port))  # gets no answer
            client.sendto(b"AMC-FIND?", ("127.0.0.1", self.udp_port))
            data, _ = client.recvfrom(1024)
        finally:
            client.close()
        answer = json.loads(data)
        self.assertEqual("amazon-music-connector-for-hitster", answer["app"])
        self.assertEqual(self.port, answer["port"])
        self.assertEqual(self.info["instanceId"], answer["id"])
        self.assertEqual(self.info["hostname"], answer["name"])
        self.assertIn("open", answer)
        self.assertNotIn("token", answer)
        self.assertIsNone(self.info["discoveryError"])

    def test_info_and_tokens(self):
        self.assertTrue(self.info["serverRunning"])
        self.assertEqual(self.info["port"], self.port)
        self.assertNotEqual(self.token, self.admin)
        self.assertFalse(self.info["apk"]["present"])

    def test_open_and_phone_endpoints(self):
        self.assertEqual(self.jhttp("GET", "/ping")[0], 200)
        self.assertEqual(self.jhttp("GET", "/status", token="bad")[0], 401)
        self.assertEqual(self.jhttp("GET", "/status", token=self.token)[0], 200)
        self.assertEqual(self.jhttp("POST", "/play", {"title": "x"}, token=self.token)[0], 400)

    def test_play_from_phone_and_admin(self):
        t = self.addon_answers("play", {"ok": False, "error": "not implemented"})
        status, body = self.jhttp("POST", "/play", {"title": "Start Me Up", "artist": "The Rolling Stones",
                                                    "year": 1981, "card": "de/00003"}, token=self.token)
        t.join()
        self.assertEqual((status, body["error"]), (502, "not implemented"))
        self.assertEqual(self.seen["title"], "Start Me Up")
        t = self.addon_answers("play", {"ok": True, "message": "playing"})
        status, body = self.jhttp("POST", "/play", {"title": "A", "artist": "B"}, token=self.admin)
        t.join()
        self.assertEqual((status, body["message"]), (200, "playing"))
        sources = [e["source"] for e in self.jhttp("GET", "/log", token=self.admin)[1]["entries"]
                   if e["kind"] == "request"]
        self.assertEqual(sources[-2:], ["phone", "admin"])

    def test_debug_endpoints_need_admin_and_loopback(self):
        self.assertEqual(self.jhttp("GET", "/info", token=self.token)[0], 403)
        self.assertEqual(self.jhttp("GET", "/info")[0], 401)
        status, info = self.jhttp("GET", "/info", token=self.admin)
        self.assertEqual(status, 200)
        self.assertNotIn("token", info)
        self.assertNotIn(self.admin, json.dumps(info))
        lan = [a for a in self.info["addresses"]]
        if lan:  # a request from the LAN address is not loopback, even with the admin token
            self.assertEqual(self.jhttp("GET", "/info", token=self.admin, host=lan[0])[0], 403)
            self.assertEqual(self.jhttp("POST", "/dom", {}, token=self.admin, host=lan[0])[0], 403)

    def test_dom_is_forwarded(self):
        t = self.addon_answers("dom", {"ok": True, "url": "https://music.amazon.de/", "data": "<html></html>"})
        status, body = self.jhttp("POST", "/dom", {"selector": "music-app >>> input", "mode": "html",
                                                   "maxBytes": 1000, "bogus": 1}, token=self.admin)
        t.join()
        self.assertEqual((status, body["data"]), (200, "<html></html>"))
        self.assertEqual(self.seen["selector"], "music-app >>> input")
        self.assertNotIn("bogus", self.seen)
        self.assertEqual(self.jhttp("POST", "/dom", {"mode": "evil"}, token=self.admin)[0], 400)

    def test_navigate_and_wait_for(self):
        t = self.addon_answers("navigate", {"ok": True, "url": "https://music.amazon.de/search/x"})
        status, body = self.jhttp("POST", "/navigate", {"url": "https://music.amazon.de/search/x"},
                                  token=self.admin)
        t.join()
        self.assertEqual((status, body["ok"]), (200, True))
        for bad in ("https://evil.example/", "http://music.amazon.de/", "javascript:alert(1)", 5):
            self.assertEqual(self.jhttp("POST", "/navigate", {"url": bad}, token=self.admin)[0], 400)
        self.assertEqual(self.jhttp("POST", "/navigate", {"url": "https://music.amazon.de/"},
                                    token=self.token)[0], 403)
        t = self.addon_answers("dom", {"ok": True, "data": []})
        self.jhttp("POST", "/dom", {"mode": "summary", "waitFor": "[data-testid=x]", "timeout": 5000},
                   token=self.admin)
        t.join()
        self.assertEqual((self.seen["waitFor"], self.seen["timeout"]), ("[data-testid=x]", 5000))

    def test_control_is_forwarded_and_validated(self):
        t = self.addon_answers("control", {"ok": True, "message": "Done."})
        status, body = self.jhttp("POST", "/control", {"action": "back10", "junk": 1}, token=self.token)
        t.join()
        self.assertEqual((status, body["message"]), (200, "Done."))
        self.assertEqual(self.seen["action"], "back10")
        self.assertNotIn("junk", self.seen)
        t = self.addon_answers("control", {"ok": True, "position": 20, "duration": 200, "playing": True})
        status, body = self.jhttp("POST", "/control", {"action": "seek", "position": 42.5}, token=self.token)
        t.join()
        self.assertEqual((status, body["duration"], self.seen["position"]), (200, 200, 42.5))
        t = self.addon_answers("control", {"ok": True, "position": 21, "duration": 200, "playing": True})
        self.assertEqual(self.jhttp("POST", "/control", {"action": "state"}, token=self.token)[1]["position"], 21)
        t.join()
        for bad in ({"action": "eval"}, {}, {"action": 5}, {"action": "seek"}, {"action": "seek", "position": "1"},
                    {"action": "seek", "position": -1}, {"action": "seek", "position": True}):
            self.assertEqual(self.jhttp("POST", "/control", bad, token=self.token)[0], 400)
        self.assertEqual(self.jhttp("POST", "/control", {"action": "stop"}, token="wrong")[0], 401)

    def test_act_is_forwarded_and_validated(self):
        t = self.addon_answers("act", {"ok": True, "matched": 1})
        status, body = self.jhttp("POST", "/act", {"action": "type", "selector": "input", "text": "Abba",
                                                   "index": 0, "junk": 1}, token=self.admin)
        t.join()
        self.assertEqual((status, body["matched"]), (200, 1))
        self.assertEqual(self.seen["text"], "Abba")
        self.assertNotIn("junk", self.seen)
        for bad in ({"action": "eval", "selector": "a"}, {"action": "click"}, {"action": "click", "selector": " "},
                    {"action": "type", "selector": "a", "text": "x" * 400}):
            self.assertEqual(self.jhttp("POST", "/act", bad, token=self.admin)[0], 400)
        self.assertEqual(self.jhttp("POST", "/act", {"action": "click", "selector": "a"}, token=self.token)[0], 403)

    def test_reload_is_forwarded_for_admin_only(self):
        t = self.addon_answers("reload", {"ok": True, "message": "Reloading the add-on."})
        self.assertEqual(self.jhttp("POST", "/reload", {}, token=self.admin)[0], 200)
        t.join()
        self.assertEqual(self.jhttp("POST", "/reload", {}, token=self.token)[0], 403)
        self.assertEqual(self.jhttp("POST", "/reload", {})[0], 401)

    def test_pairing_flow(self):
        status, ping = self.jhttp("GET", "/ping")
        self.assertEqual(len(ping["id"]), 32)
        self.assertTrue(ping["name"])
        status, body = self.jhttp("POST", "/pair", {"name": "Pixel 7"})
        self.assertEqual(status, 200)
        pid = body["id"]
        # the options page sees the request, and the add-on is told about it
        self.assertEqual(self.wait_for(lambda m: m.get("event") == "pairRequest" and m["name"] == "Pixel 7")["id"], pid)
        self.assertEqual([p["name"] for p in self.command("getInfo")["result"]["pairing"]], ["Pixel 7"])
        # asking again returns the same request
        self.assertEqual(self.jhttp("POST", "/pair", {"name": "Pixel 7"})[1]["id"], pid)
        status, state = self.jhttp("GET", "/pair/" + pid)
        self.assertEqual(state["state"], "pending")
        self.assertNotIn("token", state)
        # the user allows it on the options page
        self.assertTrue(self.command("pairDecide", id=pid, allow=True)["ok"])
        status, state = self.jhttp("GET", "/pair/" + pid)
        self.assertEqual((state["state"], state["token"]), ("approved", self.token))
        self.assertEqual(self.command("getInfo")["result"]["pairing"], [])
        # a request cannot be decided twice, and a wrong id learns nothing
        self.assertFalse(self.command("pairDecide", id=pid, allow=False)["ok"])
        self.assertEqual(self.jhttp("GET", "/pair/nope")[1], {"state": "expired", "name": ping["name"]})

    def test_pairing_can_be_refused_and_the_developer_can_decide(self):
        pid = self.jhttp("POST", "/pair", {"name": "Tablet"})[1]["id"]
        self.assertEqual(self.jhttp("POST", "/pairing", {"id": pid, "allow": False}, token=self.token)[0], 403)
        self.assertEqual(self.jhttp("POST", "/pairing", {"id": pid, "allow": False}, token=self.admin)[0], 200)
        state = self.jhttp("GET", "/pair/" + pid)[1]
        self.assertEqual(state["state"], "denied")
        self.assertNotIn("token", state)

    def test_open_access_needs_no_token_but_debug_still_does(self):
        self.assertFalse(self.jhttp("GET", "/ping")[1]["open"])
        self.assertEqual(self.jhttp("GET", "/status")[0], 401)
        self.assertTrue(self.command("setConfig", openAccess=True)["result"]["openAccess"])
        try:
            self.assertTrue(self.jhttp("GET", "/ping")[1]["open"])
            self.assertEqual(self.jhttp("GET", "/status")[0], 200)
            self.assertEqual(self.jhttp("GET", "/status", token="wrong")[0], 200)
            t = self.addon_answers("play", {"ok": True, "message": "playing"})
            self.assertEqual(self.jhttp("POST", "/play", {"title": "A", "artist": "B"})[0], 200)
            t.join()
            # the debug interface is not opened by this
            for path in ("/info", "/tabs", "/log"):
                self.assertEqual(self.jhttp("GET", path)[0], 401)
                self.assertEqual(self.jhttp("GET", path, token=self.token)[0], 403)
            self.assertEqual(self.jhttp("POST", "/dom", {})[0], 401)
            self.assertEqual(self.jhttp("POST", "/act", {"action": "click", "selector": "a"})[0], 401)
            self.assertEqual(self.jhttp("POST", "/reload", {})[0], 401)
        finally:
            self.command("setConfig", openAccess=False)
        self.assertEqual(self.jhttp("GET", "/status")[0], 401)
        self.assertEqual(self.jhttp("POST", "/play", {"title": "A", "artist": "B"})[0], 401)

    def test_tabs_is_forwarded(self):
        t = self.addon_answers("tabs", {"ok": True, "tabs": []})
        self.assertEqual(self.jhttp("GET", "/tabs", token=self.admin)[1]["tabs"], [])
        t.join()

    def test_log_long_poll_and_events(self):
        rev = self.jhttp("GET", "/log", token=self.admin)[1]["rev"]
        threading.Timer(0.3, lambda: self.send({"cmd": "event", "level": "info", "text": "hello"})).start()
        started = time.time()
        body = self.jhttp("GET", "/log?since=%d&wait=10" % rev, token=self.admin)[1]
        self.assertLess(time.time() - started, 5)
        self.assertEqual([e["text"] for e in body["entries"]], ["hello"])
        self.assertEqual(self.jhttp("GET", "/log?since=%d" % body["rev"], token=self.admin)[1]["entries"], [])

    def test_apk_upload_and_download(self):
        self.assertEqual(self.http("GET", "/app.apk")[0], 404)
        data = os.urandom(300000)
        digest = hashlib.sha256(data).hexdigest()
        size = 120000
        chunks = [data[i:i + size] for i in range(0, len(data), size)]
        for i, chunk in enumerate(chunks):
            reply = self.command("apkChunk", offset=i * size, total=len(data), sha256=digest,
                                 version="9.9.9", last=i == len(chunks) - 1,
                                 data=base64.b64encode(chunk).decode())
            self.assertTrue(reply["ok"], reply)
        status, body, headers = self.http("GET", "/app/Amazon-Music-Desktop-Connector-for-Hitster-9.9.9.apk")
        self.assertEqual(status, 200)
        self.assertEqual(body, data)
        self.assertEqual(headers["Content-Type"], "application/vnd.android.package-archive")
        self.assertEqual(headers["Content-Disposition"], 'attachment; filename="Amazon-Music-Desktop-Connector-for-Hitster-9.9.9.apk"')
        page = self.http("GET", "/app")[1].decode()
        # the link carries the version, a phone browser then sees a new file for a new build
        self.assertIn('href="/app/Amazon-Music-Desktop-Connector-for-Hitster-9.9.9.apk"', page)
        self.assertEqual(self.http("GET", "/app.apk")[1], data)  # the fixed address still works
        self.assertIn("9.9.9", page)
        self.assertTrue(self.command("getInfo")["result"]["apk"]["present"])
        # a corrupt upload is refused and does not replace the good file
        bad = self.command("apkChunk", offset=0, total=5, sha256="0" * 64, version="x", last=True,
                           data=base64.b64encode(b"12345").decode())
        self.assertFalse(bad["ok"])
        self.assertEqual(self.http("GET", "/app.apk")[1], data)

    def test_set_config(self):
        reply = self.command("setConfig", regenerateToken=True)
        self.assertTrue(reply["ok"])
        self.assertNotEqual(reply["result"]["token"], self.token)
        # restore the token for the other tests
        with open(os.path.join(self.dir.name, "config.json")) as f:
            type(self).token = json.load(f)["token"]
        self.assertFalse(self.command("setConfig", port=80)["ok"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
