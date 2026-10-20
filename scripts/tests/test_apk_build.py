"""Artifact-boundary regressions; no Gradle, SDK or native compilation required."""

import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
import unittest
import zipfile


SCRIPTS = Path(__file__).resolve().parents[1]
LIBRARIES = ("libcombray_engine.so", "libc++_shared.so")
ELF_MACHINES = {"arm64-v8a": 183, "x86_64": 62}


class ApkBuildTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="combray build ")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "scripts").mkdir()
        (self.root / "app").mkdir()
        shutil.copy2(SCRIPTS / "build.sh", self.root / "scripts/build.sh")
        shutil.copy2(SCRIPTS / "verify-apk.py", self.root / "scripts/verify-apk.py")
        (self.root / "app/build.gradle.kts").write_text('versionName = "0.1.0"\n')
        # Keep the build boundary real: the script stages the APK produced by its build command.
        gradle = self.root / "gradlew"
        gradle.write_text(
            '#!/bin/sh\nset -eu\nvariant=debug\n'
            '[ "$1" != assembleRelease ] || variant=release\n'
            'dir="app/build/outputs/apk/$variant"\nmkdir -p "$dir"\n'
            'cp fixture.apk "$dir/app-$variant.apk"\n'
            'cp fixture-metadata.json "$dir/output-metadata.json"\n'
        )
        gradle.chmod(0o755)
        # The engine build is its own script; record the ABI it was asked for.
        self.engine = self.root / "scripts/build-native.sh"
        self.engine.write_text('#!/bin/sh\necho "${COMBRAY_ABI:-unset}" >> engine-builds\n')
        self.engine.chmod(0o755)
        self.env = {key: value for key, value in os.environ.items() if not key.startswith("COMBRAY_")}

    def fixture(self, abi="x86_64", libraries=LIBRARIES, machine=None, variant="debug", output=None):
        header = struct.pack(
            "<16sHHIQQQIHHHHHH",
            b"\x7fELF\x02\x01\x01" + bytes(9),
            3, ELF_MACHINES[abi] if machine is None else machine, 1,
            0, 0, 0, 0, 64, 0, 0, 0, 0, 0,
        )
        with zipfile.ZipFile(self.root / "fixture.apk", "w") as apk:
            apk.writestr("AndroidManifest.xml", b"test manifest")
            for library in libraries:
                apk.writestr(f"lib/{abi}/{library}", header)
        (self.root / "fixture-metadata.json").write_text(json.dumps({
            "artifactType": {"type": "APK"},
            "variantName": variant,
            "applicationId": "ch.lkmc.combray" + (".debug" if variant == "debug" else ""),
            "elements": [{"versionName": "0.1.0", "outputFile": output or f"app-{variant}.apk"}],
        }))

    def build(self, *arguments):
        return subprocess.run(
            ["bash", "scripts/build.sh", *arguments], cwd=self.root, env=self.env,
            text=True, capture_output=True, check=False,
        )

    def test_x86_apk_is_named_x86(self):
        self.fixture()
        result = self.build("--debug")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertTrue((self.root / "dist/combray-debug-v0.1.0-android-x86_64.apk").is_file())
        self.assertFalse((self.root / "dist/combray-debug-v0.1.0-android-arm64.apk").exists())
        artifact = self.root / "dist/combray-debug-v0.1.0-android-x86_64.apk"
        self.assertEqual(
            artifact.with_suffix(".apk.sha256").read_text(),
            f"{hashlib.sha256(artifact.read_bytes()).hexdigest()}  {artifact.name}\n",
        )

    def test_arm64_apk_is_named_arm64(self):
        self.fixture(abi="arm64-v8a")
        result = self.build("--debug")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertTrue((self.root / "dist/combray-debug-v0.1.0-android-arm64.apk").is_file())

    def engine_builds(self):
        path = self.root / "engine-builds"
        return path.read_text().split() if path.exists() else []

    def test_the_engine_is_built_for_phones_by_default(self):
        self.fixture(abi="arm64-v8a")
        result = self.build("--debug")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(self.engine_builds(), ["arm64"])

    def test_the_engine_is_built_for_the_requested_abi(self):
        self.fixture()
        self.env["COMBRAY_ABI"] = "x86_64"
        result = self.build("--debug")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(self.engine_builds(), ["x86_64"])

    def test_an_unknown_abi_is_rejected_before_building(self):
        self.fixture()
        self.env["COMBRAY_ABI"] = "armv7"
        result = self.build("--debug")
        self.assertEqual(result.returncode, 2)
        self.assertIn("COMBRAY_ABI", result.stderr)
        self.assertEqual(self.engine_builds(), [])

    def test_a_failed_engine_build_stops_before_gradle(self):
        self.fixture()
        self.engine.write_text("#!/bin/sh\nexit 3\n")
        result = self.build("--debug")
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.root / "app/build").exists())
        self.assertFalse((self.root / "dist").exists())

    def test_check_builds_nothing(self):
        self.fixture()
        result = self.build("--check", "--debug")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("COMBRAY_ABI=arm64 scripts/build-native.sh", result.stdout)
        self.assertEqual(self.engine_builds(), [])

    def test_missing_servo_is_rejected(self):
        self.fixture(libraries=("libc++_shared.so",))
        result = self.build("--debug")
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("libcombray_engine.so", result.stderr)
        self.assertFalse((self.root / "dist").exists())

    def test_missing_cpp_runtime_is_rejected(self):
        self.fixture(libraries=("libcombray_engine.so",))
        result = self.build("--debug")
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("libc++_shared.so", result.stderr)

    def test_mislabelled_elf_is_rejected(self):
        self.fixture(abi="arm64-v8a", machine=ELF_MACHINES["x86_64"])
        result = self.build("--debug")
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("ELF", result.stderr)

    def test_stale_apk_is_rejected(self):
        self.fixture(output="app-debug-other.apk")
        result = self.build("--debug")
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("metadata", result.stderr)

    def test_release_needs_explicit_signing(self):
        self.fixture(variant="release")
        result = self.build()
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("COMBRAY_KEYSTORE_FILE", result.stderr)
        self.assertFalse((self.root / "dist").exists())

    def test_malformed_metadata_is_rejected(self):
        self.fixture()
        (self.root / "fixture-metadata.json").write_text("[]")
        result = self.build("--debug")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("metadata", result.stderr)
        self.assertNotIn("Traceback", result.stderr)

    def test_mixed_native_abis_are_rejected(self):
        self.fixture()
        with zipfile.ZipFile(self.root / "fixture.apk", "a") as apk:
            apk.writestr("lib/arm64-v8a/libother.so", b"another ABI")
        result = self.build("--debug")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("exactly one", result.stderr)

    def test_truncated_library_is_rejected(self):
        self.fixture()
        with zipfile.ZipFile(self.root / "fixture.apk", "w") as apk:
            for library in LIBRARIES:
                apk.writestr(f"lib/x86_64/{library}", b"\x7fELF")
        result = self.build("--debug")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("ELF", result.stderr)

    def test_release_rejects_partial_signing_configuration(self):
        self.fixture(variant="release")
        self.env["COMBRAY_KEYSTORE_FILE"] = "/unused/test.jks"
        result = self.build()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("COMBRAY_KEYSTORE_PASSWORD", result.stderr)

    def test_expected_architecture_checks_the_apk(self):
        self.fixture()
        result = subprocess.run(
            ["python3", "-I", str(SCRIPTS / "verify-apk.py"),
             str(self.root / "fixture.apk"), "--expected-arch", "arm64"],
            text=True, capture_output=True, check=False,
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("contains x86_64, expected arm64", result.stderr)

    def test_failed_copy_preserves_the_previous_artifact(self):
        self.fixture()
        dist = self.root / "dist"
        dist.mkdir()
        artifact = dist / "combray-debug-v0.1.0-android-x86_64.apk"
        checksum = artifact.with_suffix(".apk.sha256")
        artifact.write_bytes(b"previous complete APK")
        checksum.write_text("previous checksum")

        binaries = self.root / "bin"
        binaries.mkdir()
        real_cp = shutil.which("cp")
        self.assertIsNotNone(real_cp)
        failing_cp = binaries / "cp"
        failing_cp.write_text(
            '#!/bin/sh\ncase "$2" in dist/*) printf truncated > "$2"; exit 1;; esac\n'
            f'exec "{real_cp}" "$@"\n'
        )
        failing_cp.chmod(0o755)
        self.env["PATH"] = str(binaries) + os.pathsep + self.env["PATH"]
        result = self.build("--debug")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(artifact.read_bytes(), b"previous complete APK")
        self.assertEqual(checksum.read_text(), "previous checksum")
        self.assertEqual(set(dist.iterdir()), {artifact, checksum})


if __name__ == "__main__":
    unittest.main()
