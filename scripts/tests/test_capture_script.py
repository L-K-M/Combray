"""Run capture's dependency-free Node regressions in the existing script gate."""

from pathlib import Path
import shutil
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[2]


@unittest.skipUnless(shutil.which("node"), "Node is needed for the capture script tests")
class CaptureScriptTest(unittest.TestCase):
    def test_rendered_text_boundaries_and_utf8_budget(self):
        result = subprocess.run(
            ["node", "--test", str(ROOT / "native/src/test/capture.test.mjs")],
            capture_output=True,
            text=True,
            timeout=30,
            check=False,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
