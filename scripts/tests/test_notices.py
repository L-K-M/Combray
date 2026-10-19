"""The committed engine notices match their inputs; no cargo-about or native build required."""

import hashlib
import importlib.util
import json
from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]
NOTICES = ROOT / "app/src/main/assets/notices/native.json"
REGENERATE = "inputs changed: run scripts/generate-notices.py on the native build host"


class NoticesTest(unittest.TestCase):
    def setUp(self):
        self.notices = json.loads(NOTICES.read_text())

    def test_notices_were_generated_from_the_committed_inputs(self):
        inputs = dict(self.notices["inputs"])
        ndk = inputs.pop("ndk")
        self.assertEqual(set(inputs), set(load_generator().HASHED_INPUTS), REGENERATE)
        for name, digest in inputs.items():
            self.assertEqual(hashlib.sha256((ROOT / name).read_bytes()).hexdigest(), digest, f"{name}: {REGENERATE}")
        pinned = re.search(r'^export COMBRAY_NDK_VERSION="([^"]+)"', (ROOT / "scripts/m0/env.sh").read_text(), re.M)
        self.assertIsNotNone(pinned, "no COMBRAY_NDK_VERSION in scripts/m0/env.sh")
        self.assertEqual(pinned.group(1), ndk, f"NDK: {REGENERATE}")

    def test_every_component_has_a_license_text(self):
        count = len(self.notices["texts"])
        self.assertTrue(self.notices["components"])
        for component in self.notices["components"]:
            self.assertTrue(component["texts"], component["name"])
            self.assertTrue(all(0 <= index < count for index in component["texts"]), component["name"])
        self.assertIn("Apache-2.0", self.notices["spdx"])
        self.assertTrue(all(0 <= index < count for index in self.notices["spdx"].values()))

    def test_the_engine_and_its_bundled_libraries_are_named(self):
        names = {component["name"] for component in self.notices["components"]}
        for name in ("servo", "SpiderMonkey", "ANGLE", "FreeType", "HarfBuzz", "LLVM libc++"):
            self.assertIn(name, names)


def load_generator():
    spec = importlib.util.spec_from_file_location("generate_notices", ROOT / "scripts/generate-notices.py")
    generator = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(generator)
    return generator


class GeneratorTest(unittest.TestCase):
    def setUp(self):
        self.generator = load_generator()

    def test_a_text_at_index_zero_is_found(self):
        self.assertEqual(self.generator.spdx_text({"MPL-2.0": 0}, "MPL-2.0"), 0)

    def test_a_missing_text_stops_the_generator(self):
        with self.assertRaises(SystemExit):
            self.generator.spdx_text({}, "MPL-2.0")


if __name__ == "__main__":
    unittest.main()
