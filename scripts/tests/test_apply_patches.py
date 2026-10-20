"""Servo's patches land even though servo-checkout/ sits inside the Combray repository."""

from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/m0/apply-patches.sh"

# Shaped like the git format-patch files in native/servo/patches/.
PATCH = """\
diff --git a/components/lib.rs b/components/lib.rs
--- a/components/lib.rs
+++ b/components/lib.rs
@@ -1 +1 @@
-pub fn original() {}
+pub fn patched() {}
"""


class ApplyPatchesTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(prefix="combray patches ")
        self.addCleanup(temp.cleanup)
        # A clone with an extracted, ignored source tree inside it, like servo-checkout/.
        self.repo = Path(temp.name)
        subprocess.run(["git", "init", "-q", str(self.repo)], check=True)
        (self.repo / ".gitignore").write_text("/servo-checkout/\n")
        self.tree = self.repo / "servo-checkout/servo"
        (self.tree / "components").mkdir(parents=True)
        self.source = self.tree / "components/lib.rs"
        self.source.write_text("pub fn original() {}\n")
        self.patch = self.repo / "0001-test.patch"
        self.patch.write_text(PATCH)

    def apply(self):
        return subprocess.run(
            ["bash", str(SCRIPT), str(self.tree), str(self.patch)], text=True, capture_output=True, check=False,
        )

    def test_a_patch_applies_inside_an_enclosing_repository(self):
        result = self.apply()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(self.source.read_text(), "pub fn patched() {}\n")

    def test_a_patch_that_does_not_apply_fails(self):
        self.source.write_text("pub fn something_else() {}\n")
        result = self.apply()
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
