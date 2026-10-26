"""The committed engine notices match their inputs; no cargo-about or native build required."""

import hashlib
import importlib.util
import json
from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]
NOTICES = ROOT / "app/src/main/assets/notices/native.json"
RUST_NOTICES = ROOT / "app/src/main/assets/notices/Rust-Licenses.html"
REGENERATE = "inputs changed: run scripts/generate-notices.py on the native build host"
# An SPDX template's copyright line, which cargo-about prints when a crate ships no license file.
TEMPLATE = re.compile(r"<(owner|copyright holders?)>", re.IGNORECASE)


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
        required = (
            "servo", "SpiderMonkey", "ANGLE", "FreeType", "HarfBuzz", "LLVM libc++",
            "LLVM libunwind and compiler-rt builtins", "Rust standard library",
            "Public Suffix List", "Chromium HSTS preload list", "Web Bluetooth GATT blocklist",
        )
        self.assertEqual([name for name in required if name not in names], [])

    def test_a_servo_pin_or_toolchain_change_needs_regenerating(self):
        for name in ("scripts/m0/servo-pin.sh", "native/rust-toolchain.toml"):
            self.assertIn(name, self.notices["inputs"])

    def component_text(self, name):
        components = [component for component in self.notices["components"] if component["name"] == name]
        self.assertEqual(len(components), 1, name)
        return "".join(self.notices["texts"][index] for index in components[0]["texts"])

    def test_the_llvm_runtime_notice_is_complete(self):
        for name in ("LLVM libc++", "LLVM libunwind and compiler-rt builtins"):
            text = self.component_text(name)
            self.assertIn("LLVM Exceptions to the Apache 2.0 License", text, name)
            self.assertIn("University of Illinois", text, name)
            self.assertGreater(len(text), 10_000, name)

    def test_the_rust_standard_library_notice_is_the_pinned_toolchains(self):
        channel = re.search(r'^channel = "([^"]+)"', (ROOT / "native/rust-toolchain.toml").read_text(), re.M)
        self.assertIsNotNone(channel, "no channel in native/rust-toolchain.toml")
        self.assertIn("The Rust Standard Library is dual-licensed", self.component_text("Rust standard library"))
        component = next(c for c in self.notices["components"] if c["name"] == "Rust standard library")
        self.assertEqual(component["version"], channel.group(1), REGENERATE)
        self.assertIn(b"Copyright notices for The Rust Standard Library", RUST_NOTICES.read_bytes())

    def test_no_component_is_shown_with_a_license_template(self):
        templated = [
            f"{component['name']} {component['version']}" for component in self.notices["components"]
            if any(TEMPLATE.search(self.notices["texts"][index]) for index in component["texts"])
        ]
        self.assertEqual(templated, [], "add the real license texts to native/bundled-notices.toml")


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
