"""install.sh's device check, against a fake SDK adb and Gradle; no device or SDK required."""

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


SCRIPTS = Path(__file__).resolve().parents[1]

# A fake adb: `devices` lists $FAKE_ADB_DEVICES, `version` reports $FAKE_ADB_VERSION, and every
# call is recorded in adb-calls.
FAKE_ADB = """#!/bin/sh
echo "$0 $*" >> "$FAKE_ROOT/adb-calls"
case "$1" in
    devices) printf 'List of devices attached\\n%b' "$FAKE_ADB_DEVICES" ;;
    version) printf 'Android Debug Bridge version 1.0.41\\nVersion %s\\n' "$FAKE_ADB_VERSION" ;;
esac
"""


class InstallTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="combray install ")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "scripts").mkdir()
        (self.root / "app").mkdir()
        for script in ("install.sh", "build.sh"):
            shutil.copy2(SCRIPTS / script, self.root / "scripts" / script)
        (self.root / "app/build.gradle.kts").write_text('versionName = "0.1.0"\n')
        gradle = self.root / "gradlew"
        gradle.write_text('#!/bin/sh\necho "$*" >> gradle-calls\n')
        gradle.chmod(0o755)
        engine = self.root / "scripts/build-native.sh"
        engine.write_text('#!/bin/sh\necho built >> engine-builds\n')
        engine.chmod(0o755)

        self.sdk = self.root / "sdk"
        self.adb = self.fake_adb(self.sdk / "platform-tools")
        (self.sdk / "platforms").mkdir()
        (self.root / "local.properties").write_text(f"sdk.dir={self.sdk}\n")
        self.path = self.root / "path"
        self.path.mkdir()
        self.env = {key: value for key, value in os.environ.items()
                    if not key.startswith(("COMBRAY_", "ANDROID_"))}
        self.env.update(FAKE_ROOT=str(self.root), FAKE_ADB_DEVICES="", FAKE_ADB_VERSION="36.0.0")
        self.env["PATH"] = f"{self.path}{os.pathsep}{self.env['PATH']}"

    def fake_adb(self, directory):
        directory.mkdir(parents=True)
        adb = directory / "adb"
        adb.write_text(FAKE_ADB)
        adb.chmod(0o755)
        return adb

    def run_script(self, script, *arguments, devices=""):
        self.env["FAKE_ADB_DEVICES"] = devices
        return subprocess.run(
            ["bash", f"scripts/{script}", *arguments], cwd=self.root, env=self.env,
            text=True, capture_output=True, check=False,
        )

    def calls(self, name):
        path = self.root / name
        return path.read_text().splitlines() if path.exists() else []

    def assert_refused(self, result, message):
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn(message, result.stderr)
        self.assertEqual(self.calls("gradle-calls"), [], "Gradle ran")

    def test_no_device_is_explained_before_gradle_runs(self):
        result = self.run_script("install.sh")
        self.assert_refused(result, "adb sees no phone or emulator")
        self.assertIn("USB debugging", result.stderr)

    def test_an_unauthorized_phone_asks_to_accept_the_prompt(self):
        result = self.run_script("install.sh", devices="R5CT12345\tunauthorized\n")
        self.assert_refused(result, "has not allowed USB debugging")

    def test_an_offline_phone_says_so(self):
        result = self.run_script("install.sh", devices="R5CT12345\toffline\n")
        self.assert_refused(result, "offline")

    def test_a_device_without_permission_says_so(self):
        result = self.run_script("install.sh", devices="R5CT12345\tno permissions (missing udev rules?)\n")
        self.assert_refused(result, "no permission")

    def test_a_usable_device_installs_and_launches(self):
        result = self.run_script("install.sh", devices="R5CT12345\tdevice\n")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(self.calls("gradle-calls"), ["installDebug"])
        self.assertIn(f"{self.adb} shell monkey -p ch.lkmc.combray.debug -c android.intent.category.LAUNCHER 1",
                      self.calls("adb-calls"))

    def test_an_unauthorized_phone_beside_a_usable_one_installs(self):
        result = self.run_script("install.sh", devices="emulator-5554\tdevice\nR5CT12345\tunauthorized\n")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(self.calls("gradle-calls"), ["installDebug"])

    def test_the_device_check_alone_builds_nothing(self):
        result = self.run_script("install.sh", "--device-check", devices="R5CT12345\tdevice\n")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("adb sees a device", result.stdout)
        self.assertEqual(self.calls("gradle-calls"), [])

    def test_the_sdk_adb_is_used_before_the_one_on_path(self):
        on_path = self.fake_adb(self.path / "bin")
        self.env["PATH"] = f"{on_path.parent}{os.pathsep}{self.env['PATH']}"
        self.run_script("install.sh", devices="R5CT12345\tdevice\n")
        self.assertTrue(self.calls("adb-calls"))
        self.assertTrue(all(call.startswith(str(self.adb)) for call in self.calls("adb-calls")), self.calls("adb-calls"))

    def test_a_second_adb_version_on_path_is_named(self):
        on_path = self.fake_adb(self.path / "bin")
        on_path.write_text(FAKE_ADB.replace('"$FAKE_ADB_VERSION"', '"34.0.5"'))
        self.env["PATH"] = f"{on_path.parent}{os.pathsep}{self.env['PATH']}"
        result = self.run_script("install.sh")
        self.assert_refused(result, f"Another adb is first on PATH ({on_path})")

    def test_the_same_adb_version_on_path_is_not_named(self):
        on_path = self.fake_adb(self.path / "bin")
        self.env["PATH"] = f"{on_path.parent}{os.pathsep}{self.env['PATH']}"
        result = self.run_script("install.sh")
        self.assert_refused(result, "adb sees no phone or emulator")
        self.assertNotIn("Another adb", result.stderr)

    def test_build_install_checks_the_device_before_building_the_engine(self):
        result = self.run_script("build.sh", "--debug", "--install")
        self.assert_refused(result, "adb sees no phone or emulator")
        self.assertEqual(self.calls("engine-builds"), [], "the engine was built")


if __name__ == "__main__":
    unittest.main()
