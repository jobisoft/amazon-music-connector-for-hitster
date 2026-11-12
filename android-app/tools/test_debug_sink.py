#!/usr/bin/env python3
"""Tests of the discovery answer of debug_sink.py: python3 -m unittest tools/test_debug_sink.py"""
import os
import socket
import sys
import threading
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import debug_sink  # noqa: E402


class DiscoveryTest(unittest.TestCase):
    def test_answers_the_request_with_the_http_port(self):
        server = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        server.bind(("127.0.0.1", 0))
        threading.Thread(target=debug_sink.answer_discovery, args=(8781, 0, server), daemon=True).start()
        client = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        client.settimeout(2)
        client.sendto(b"something else", server.getsockname())
        client.sendto(b"AMSTER-SINK?", server.getsockname())
        data, _ = client.recvfrom(64)
        self.assertEqual(b"AMSTER-SINK 8781", data)  # the first datagram got no answer
        client.close()


if __name__ == "__main__":
    unittest.main()
