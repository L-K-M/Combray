"""The fixture server (testing/fixtures/server.py): its request log keeps each check's key out,
its held pages wait for their release, and its fake report service answers as the real one."""

import base64
import contextlib
from concurrent.futures import ThreadPoolExecutor
import copy
import hashlib
import http.client
import importlib.util
import io
import http.server
import json
import os
from pathlib import Path
import struct
import threading
import time
import unittest
from unittest.mock import Mock
import urllib.error
import urllib.request
import zlib

ROOT = Path(__file__).resolve().parents[2]
GOLDEN_REPORT = ROOT / "services/site-reports/tests/data/report-v1.json"


def load_server():
    spec = importlib.util.spec_from_file_location("fixture_server", ROOT / "testing/fixtures/server.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class WidePageTest(unittest.TestCase):
    """The wide page sets an initial scale only when asked: without one, it opens zoomed out."""

    def test_only_the_initial_scale_variant_has_a_viewport_meta_tag(self):
        fixture = load_server()
        for query, initial_scale in [("", False), ("?initial-scale=1", True)]:
            with self.subTest(query=query):
                request = Mock(path="/wide.html" + query)
                fixture.Handler.do_GET(request)
                status, document = request.send.call_args.args
                self.assertEqual(http.HTTPStatus.OK, status)
                self.assertIn("<title>WIDE</title>", document)
                self.assertIn("width:1080px", document)
                self.assertEqual(initial_scale, "name='viewport'" in document)


class AgentPageTest(unittest.TestCase):
    """The agent page refuses what some site firewalls refuse and reports the agent it was sent."""

    SERVO = "Mozilla/5.0 (Android 10; Mobile; rv:153.0) Servo/0.7.0 Firefox/153.0"
    COMBRAY = "Mozilla/5.0 (Android 10; Mobile; rv:153.0) Gecko/153.0 Servo/0.7.0 Firefox/153.0"

    def get(self, user_agent):
        request = Mock(path="/agent.html", headers={"User-Agent": user_agent})
        load_server().Handler.do_GET(request)
        return request.send.call_args.args

    def test_a_firefox_agent_without_a_gecko_token_is_refused(self):
        for agent in [self.SERVO, "Mozilla/5.0 Firefox/153.0", self.SERVO.replace("Servo/", "like Gecko Servo/")]:
            with self.subTest(agent=agent):
                self.assertEqual((http.HTTPStatus.FORBIDDEN, "Access denied"), self.get(agent))

    def test_other_agents_get_the_page_comparing_navigator_user_agent_with_the_header(self):
        for agent in [self.COMBRAY, "curl/8.7.1", ""]:
            with self.subTest(agent=agent):
                status, document = self.get(agent)
                self.assertEqual(http.HTTPStatus.OK, status)
                self.assertIn("<title>AGENT-WAITING</title>", document)
                self.assertIn(f"navigator.userAgent === {json.dumps(agent)} ?", document)

    def test_the_header_cannot_end_the_script(self):
        status, document = self.get('x</script><script>document.title="AGENT:same"</script>')
        self.assertEqual(http.HTTPStatus.OK, status)
        self.assertEqual(1, document.count("</script>"))
        self.assertIn("x\\u003c/script>", document)


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


class PullFixtureTest(unittest.TestCase):
    def test_slow_handlers_differ_only_in_whether_the_page_refuses_the_drag(self):
        fixture = load_server()
        for query, prevents in [("", False), ("?prevent=1", True)]:
            with self.subTest(query=query):
                request = Mock(path="/pull-slow.html" + query)
                fixture.Handler.do_GET(request)
                status, document = request.send.call_args.args
                self.assertEqual(http.HTTPStatus.OK, status)
                self.assertIn("TOUCH_DELAY_MS", document)
                self.assertIn("while(performance.now()<until)", document)
                self.assertEqual(prevents, "event.preventDefault();" in document)
                self.assertIn("{passive:false}", document)


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


class ReusedCloseFixtureTest(unittest.TestCase):
    """/reused-close.html answers a connection's first request and drops a later one unanswered."""

    TIMEOUT_SECONDS = 5

    def setUp(self):
        self.fixture = load_server()
        self.fixture.log_request_line = lambda method, target, now=None: None
        self.httpd = http.server.ThreadingHTTPServer(("127.0.0.1", 0), self.fixture.Handler)
        self.worker = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.worker.start()

    def tearDown(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        self.worker.join()

    def connection(self):
        return http.client.HTTPConnection("127.0.0.1", self.httpd.server_port, timeout=self.TIMEOUT_SECONDS)

    def test_a_reused_connection_is_closed_unanswered_and_a_new_one_answered(self):
        kept = self.connection()
        kept.request("GET", "/solid/2e6b4f.html")
        first = kept.getresponse()
        first.read()
        self.assertEqual(200, first.status)

        kept.request("GET", "/reused-close.html?k=check")
        with self.assertRaises(http.client.RemoteDisconnected):
            kept.getresponse()
        kept.close()

        fresh = self.connection()
        fresh.request("GET", "/reused-close.html?k=check")
        answer = fresh.getresponse()
        self.assertEqual(200, answer.status)
        self.assertIn("<title>FRESH</title>", answer.read().decode())
        fresh.close()

        self.assertEqual({"answered": 1, "dropped": 1}, self.fixture.state["reused_close"]["check"])


class ReportServiceTest(unittest.TestCase):
    """The fake report service the device checks send to (design: real validation, own limits)."""

    TIMEOUT_SECONDS = 10
    REPORTS = "/reports/v1/reports"
    MIB = 1024 * 1024
    # The PNG's first chunk (IHDR) ends after the signature, length, type, 13 bytes and CRC.
    IHDR_END = 8 + 12 + 13

    def setUp(self):
        self.fixture = load_server()
        # Quiet: the request log goes to stdout for device runs.
        self.fixture.log_request_line = lambda method, target, now=None: None
        self.httpd = http.server.ThreadingHTTPServer(("127.0.0.1", 0), self.fixture.Handler)
        self.worker = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.worker.start()
        self.base = f"http://127.0.0.1:{self.httpd.server_port}"
        self.golden = json.loads(GOLDEN_REPORT.read_text(encoding="utf-8"))

    def tearDown(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        self.worker.join()

    def request(self, path, body=None, token=None, method=None):
        """(status, body bytes, headers) of one request; errors are answers too."""
        headers = {"Content-Type": "application/json"}
        token = self.fixture.REPORTER_TOKEN if token is None else token
        if token:
            headers["Authorization"] = f"Bearer {token}"
        request = urllib.request.Request(self.base + path, data=body, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=self.TIMEOUT_SECONDS) as response:
                return response.status, response.read(), response.headers
        except urllib.error.HTTPError as error:
            with error:
                return error.code, error.read(), error.headers

    def post(self, report, token=None):
        status, body, headers = self.request(self.REPORTS, json.dumps(report).encode("utf-8"), token)
        return status, json.loads(body), headers

    def state(self):
        return json.loads(self.request("/_admin/state", token="")[1])

    def posts(self):
        return [entry for entry in self.state()["log"] if entry["service"] == "reports" and entry["method"] == "POST"]

    def admin(self, path):
        status, body, _ = self.request(path, b"", token="")
        self.assertEqual(200, status)

    def with_png(self, report, png):
        """[report] carrying [png] (same IHDR size as the golden one) as its screenshot."""
        report = copy.deepcopy(report)
        report["screenshot"].update(
            bytes=len(png),
            sha256=hashlib.sha256(png).hexdigest(),
            data=base64.b64encode(png).decode("ascii"),
        )
        return report

    def padded_png(self, size):
        """The golden PNG with a private ancillary chunk of random bytes, [size] bytes in all."""
        png = base64.b64decode(self.golden["screenshot"]["data"])
        data = os.urandom(size - len(png) - 12)
        kind = b"fxTr"
        extra = struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
        return png[:self.IHDR_END] + extra + png[self.IHDR_END:]

    def test_whoami_names_the_service_and_the_reporter_role(self):
        status, body, _ = self.request("/reports/v1/whoami")
        self.assertEqual(200, status)
        self.assertEqual({"service": "combray-site-reports", "api": 1, "role": "reporter"}, json.loads(body))
        self.assertEqual(401, self.request("/reports/v1/whoami", token="another-token")[0])

    def test_a_report_is_stored_as_metadata_and_its_png_apart(self):
        status, body, _ = self.post(self.golden)
        self.assertEqual(201, status)
        self.assertEqual(self.golden["id"], body["id"])
        self.assertEqual("open", body["status"])

        stored = self.state()["reports"][self.golden["id"]]
        self.assertEqual(self.golden["page"], stored["page"])
        self.assertEqual(self.golden["comment"], stored["comment"])
        self.assertEqual(self.golden["client"], stored["client"])
        self.assertNotIn("data", stored["screenshot"])
        self.assertEqual(self.golden["screenshot"]["sha256"], stored["screenshot"]["sha256"])

        status, png, headers = self.request(f"/_admin/report-screenshot?id={self.golden['id']}", token="")
        self.assertEqual(200, status)
        self.assertEqual("image/png", headers["Content-Type"])
        self.assertEqual(base64.b64decode(self.golden["screenshot"]["data"]), png)
        self.assertEqual([{"service": "reports", "method": "POST", "id": self.golden["id"]}], self.posts())

    def test_a_report_without_a_screenshot_has_no_png(self):
        report = dict(self.golden, screenshot=None, screenshot_omitted="user")
        self.assertEqual(201, self.post(report)[0])
        self.assertIsNone(self.state()["reports"][report["id"]]["screenshot"])
        self.assertEqual(404, self.request(f"/_admin/report-screenshot?id={report['id']}", token="")[0])

    def test_a_repeat_is_answered_without_a_second_report_and_other_content_conflicts(self):
        self.assertEqual(201, self.post(self.golden)[0])
        status, body, _ = self.post(self.golden)
        self.assertEqual(200, status)
        self.assertEqual(self.golden["id"], body["id"])

        changed = copy.deepcopy(self.golden)
        changed["comment"] = "Another comment."
        status, body, _ = self.post(changed)
        self.assertEqual(409, status)
        self.assertEqual("id_conflict", body["error"])
        self.assertEqual(1, len(self.state()["reports"]))
        self.assertEqual(self.golden["comment"], self.state()["reports"][self.golden["id"]]["comment"])

    def test_the_real_validation_refuses_what_the_service_refuses(self):
        unknown = dict(self.golden, extra=True)
        status, body, _ = self.post(unknown)
        self.assertEqual((400, "unknown_field"), (status, body["error"]))

        client = copy.deepcopy(self.golden)
        del client["client"]["engine_revision"]
        status, body, _ = self.post(client)
        self.assertEqual((400, "invalid_client", "client.engine_revision"), (status, body["error"], body["field"]))
        self.assertEqual({}, self.state()["reports"])

    def test_a_wrong_token_is_refused(self):
        status, body, _ = self.post(self.golden, token="fixture-token")
        self.assertEqual((401, "unauthorized"), (status, body["error"]))
        self.assertEqual({}, self.state()["reports"])
        self.assertEqual([], self.posts())

    def test_a_body_at_the_limit_is_read_whole_and_one_byte_more_is_refused_unread(self):
        # JSON allows trailing whitespace, so the golden report fills the limit exactly.
        report = json.dumps(self.golden).encode("utf-8")
        at_limit = report + b" " * (self.fixture.MAX_REPORT_BODY_BYTES - len(report))
        status, _, _ = self.request(self.REPORTS, at_limit)
        self.assertEqual(201, status)

        connection = http.client.HTTPConnection("127.0.0.1", self.httpd.server_port, timeout=self.TIMEOUT_SECONDS)
        try:
            # Headers only: the server must answer without waiting for the body.
            connection.putrequest("POST", self.REPORTS)
            connection.putheader("Authorization", f"Bearer {self.fixture.REPORTER_TOKEN}")
            connection.putheader("Content-Type", "application/json")
            connection.putheader("Content-Length", str(self.fixture.MAX_REPORT_BODY_BYTES + 1))
            connection.endheaders()
            response = connection.getresponse()
            self.assertEqual(413, response.status)
            self.assertEqual("too_large", json.loads(response.read())["error"])
        finally:
            connection.close()

    def test_a_three_mib_report_is_not_cut_at_the_shared_body_cap(self):
        png = self.padded_png(int(2.3 * self.MIB))
        report = self.with_png(self.golden, png)
        body = json.dumps(report).encode("utf-8")
        self.assertGreater(len(body), 3 * self.MIB)
        self.assertGreater(len(body), self.fixture.MAX_BODY_BYTES)

        status, _, _ = self.request(self.REPORTS, body)
        self.assertEqual(201, status)
        _, stored, _ = self.request(f"/_admin/report-screenshot?id={report['id']}", token="")
        self.assertEqual(png, stored)

    def test_faults_answer_like_the_service_and_count_down(self):
        self.fixture.HANG_SECONDS = 0.1
        self.admin("/_admin/fault?service=reports&kind=server_error&count=1")
        self.assertEqual(503, self.post(self.golden)[0])
        self.assertEqual({}, self.state()["reports"])

        self.admin("/_admin/fault?service=reports&kind=too_many_requests&count=1")
        status, _, headers = self.post(self.golden)
        self.assertEqual((429, "1"), (status, headers["Retry-After"]))

        self.admin("/_admin/fault?service=reports&kind=unauthorized&count=1")
        self.assertEqual(401, self.post(self.golden)[0])

        # Stored, then answered late: the phone's retry must find it there.
        self.admin("/_admin/fault?service=reports&kind=store_then_hang&count=1")
        self.assertEqual(201, self.post(self.golden)[0])
        self.assertEqual(200, self.post(self.golden)[0])
        self.assertEqual(1, len(self.state()["reports"]))
        # Four POSTs passed the token check (the unauthorized one did not).
        self.assertEqual(4, len(self.posts()))

    def test_reset_forgets_reports_screenshots_and_faults(self):
        self.assertEqual(201, self.post(self.golden)[0])
        self.admin("/_admin/fault?service=reports&kind=server_error&count=5")
        self.admin("/_admin/reset")

        self.assertEqual({}, self.state()["reports"])
        self.assertEqual({}, self.fixture.report_screenshots)
        self.assertEqual(["none", 0], self.fixture.faults["reports"])
        self.assertEqual(201, self.post(self.golden)[0])


if __name__ == "__main__":
    unittest.main()
