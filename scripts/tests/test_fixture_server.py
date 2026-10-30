"""The fixture server's request log keeps each check's key out (testing/fixtures/server.py)."""

import contextlib
import importlib.util
import io
from pathlib import Path
import unittest

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


if __name__ == "__main__":
    unittest.main()
