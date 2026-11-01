"""The fixture server's request log keeps each check's key out (testing/fixtures/server.py)."""

import contextlib
from concurrent.futures import ThreadPoolExecutor
import importlib.util
import io
import http.server
from pathlib import Path
import threading
import time
import unittest
import urllib.request

ROOT = Path(__file__).resolve().parents[2]


def load_server():
    spec = importlib.util.spec_from_file_location("fixture_server", ROOT / "testing/fixtures/server.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class RequestLogTest(unittest.TestCase):
    def setUp(self):
        self.server = load_server()

    def test_the_key_value_is_left_out_and_the_rest_kept(self):
        logged = self.server.logged_target
        self.assertEqual(logged("/video.html?k=0123abcd"), "/video.html?k=<key>")
        self.assertEqual(logged("/media/redirect?to=clip&k=0123abcd"), "/media/redirect?to=clip&k=<key>")
        self.assertEqual(logged("/once.html?k=0123abcd&x=1"), "/once.html?k=<key>&x=1")
        self.assertEqual(logged("/media/clip.mp4?k=0123abcd#t=1"), "/media/clip.mp4?k=<key>#t=1")
        # Only the parameter named k.
        self.assertEqual(logged("/echo?q=ak=1&kk=2"), "/echo?q=ak=1&kk=2")
        self.assertEqual(logged("/solid/00ff00.html"), "/solid/00ff00.html")

    def test_a_line_has_logcats_time_format(self):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.server.log_request_line("GET", "/video.html?k=0123abcd", now=1_000_000_000.25)
        line = output.getvalue()
        self.assertRegex(line, r"^\d\d-\d\d \d\d:\d\d:\d\d\.250 GET /video\.html\?k=<key>\n$")
        self.assertNotIn("0123abcd", line)


class StopFixtureTest(unittest.TestCase):
    REQUEST_TIMEOUT_SECONDS = 5
    POLL_SECONDS = 0.01

    def setUp(self):
        self.fixture = load_server()
        self.httpd = http.server.ThreadingHTTPServer(("127.0.0.1", 0), self.fixture.Handler)
        self.worker = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.worker.start()
        self.base = f"http://127.0.0.1:{self.httpd.server_port}"

    def tearDown(self):
        for gate, _ in self.fixture.stop_load_gates.values():
            gate.set()
        self.httpd.shutdown()
        self.httpd.server_close()
        self.worker.join()

    def test_the_partial_document_reports_its_real_loading_state(self):
        with urllib.request.urlopen(self.base + "/stop-loading.html?k=fixture", timeout=self.REQUEST_TIMEOUT_SECONDS) as response:
            document = response.read().decode()
        self.assertIn("STOP-COMMITTED", document)
        self.assertIn("document.readyState", document)
        self.assertIn("/stop-loading.js?k=fixture", document)

    def test_a_blocked_script_waits_for_its_own_release(self):
        gate, counts = self.fixture.stop_load("held")

        def read_script():
            with urllib.request.urlopen(self.base + "/stop-loading.js?k=held", timeout=self.REQUEST_TIMEOUT_SECONDS) as response:
                return response.read().decode()

        with ThreadPoolExecutor(max_workers=1) as worker:
            pending = worker.submit(read_script)
            try:
                deadline = time.monotonic() + self.REQUEST_TIMEOUT_SECONDS
                while counts["requested"] == 0 and time.monotonic() < deadline:
                    time.sleep(self.POLL_SECONDS)
                self.assertEqual(1, counts["requested"])
                self.assertFalse(pending.done())
                with urllib.request.urlopen(self.base + "/_admin/finish-stop-load?k=held", data=b"", timeout=self.REQUEST_TIMEOUT_SECONDS):
                    pass
                self.assertIn("stopProbe.ran=true", pending.result(timeout=self.REQUEST_TIMEOUT_SECONDS))
            finally:
                gate.set()
        # The client can finish reading before the server increments its completion count.
        deadline = time.monotonic() + self.REQUEST_TIMEOUT_SECONDS
        while counts["finished"] == 0 and time.monotonic() < deadline:
            time.sleep(self.POLL_SECONDS)
        self.assertEqual(1, counts["finished"])


if __name__ == "__main__":
    unittest.main()
