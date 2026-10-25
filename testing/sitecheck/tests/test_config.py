"""Tests for paths, private-file parsing and the engine settings shared with the app.

Every test passes its own environment mapping and temporary directories, so the
owner's real configuration (and Hister token) is never read.
"""

from __future__ import annotations

import io
import os
import re
import stat
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from sitecheck import config
from sitecheck.config import ConfigError, HisterSettings, RunLockedError

REPO_ROOT = Path(__file__).resolve().parents[3]
ENGINE_SOURCE = REPO_ROOT / "native" / "src" / "engine.rs"

# `<receiver>.set_value("name", PrefValue::Bool(true))`, tolerating rustfmt's line
# breaks, spacing and trailing commas.
_BOOL_PREF_CALL = re.compile(
    r"\.set_value\(\s*\"(?P<name>[A-Za-z0-9_.]+)\"\s*,\s*PrefValue::Bool\(\s*(?P<value>true|false)\s*\)\s*,?\s*\)"
)
_ANY_PREF_CALL = re.compile(r"\.set_value\(\s*\"")
_LINE_COMMENT = re.compile(r"//[^\n]*")


class TempDirTest(unittest.TestCase):
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)

    def write(self, name: str, text: str, mode: int = 0o600) -> Path:
        path = self.root / name
        path.write_text(text, encoding="utf-8")
        os.chmod(path, mode)
        return path


class DirectoryTest(TempDirTest):
    def test_defaults_are_under_home(self) -> None:
        env = {"HOME": "/home/owner"}
        self.assertEqual(config.data_dir(env), Path("/home/owner/.local/share/combray-sitecheck"))
        self.assertEqual(config.config_dir(env), Path("/home/owner/.config/combray-sitecheck"))

    def test_environment_overrides(self) -> None:
        env = {
            "HOME": "/home/owner",
            config.DATA_DIR_ENV: "/srv/data",
            config.CONFIG_DIR_ENV: "~/conf",
        }
        self.assertEqual(config.data_dir(env), Path("/srv/data"))
        self.assertEqual(config.config_dir(env), Path("/home/owner/conf"))

    def test_empty_override_is_ignored(self) -> None:
        env = {"HOME": "/home/owner", config.DATA_DIR_ENV: "  "}
        self.assertEqual(config.data_dir(env), Path("/home/owner/.local/share/combray-sitecheck"))

    def test_ensure_private_dir_creates_with_0700(self) -> None:
        target = self.root / "a" / "b" / "data"
        self.assertEqual(config.ensure_private_dir(target), target)
        self.assertEqual(stat.S_IMODE(target.stat().st_mode), 0o700)

    def test_ensure_private_dir_tightens_existing(self) -> None:
        target = self.root / "data"
        target.mkdir()
        os.chmod(target, 0o755)
        config.ensure_private_dir(target)
        self.assertEqual(stat.S_IMODE(target.stat().st_mode), 0o700)

    def test_ensure_private_dir_ignores_umask(self) -> None:
        target = self.root / "data"
        # Would leave the new directory at 0500 if the mode were only passed to mkdir.
        previous = os.umask(0o222)
        try:
            config.ensure_private_dir(target)
        finally:
            os.umask(previous)
        self.assertEqual(stat.S_IMODE(target.stat().st_mode), 0o700)

    def test_ensure_private_dir_rejects_a_file(self) -> None:
        target = self.write("data", "not a directory")
        with self.assertRaises(ConfigError):
            config.ensure_private_dir(target)


class PathTest(unittest.TestCase):
    def test_page_key_is_a_short_hash(self) -> None:
        key = config.page_key("https://example.com/")
        self.assertRegex(key, r"^[0-9a-f]{16}$")
        self.assertEqual(key, config.page_key("https://example.com/"))
        self.assertNotEqual(key, config.page_key("https://example.com/a"))

    def test_page_dir_does_not_contain_the_url(self) -> None:
        run = config.run_dir(Path("/data"), "20261024T120000Z-ab12")
        page = config.page_dir(run, "https://example.com/secret-page")
        self.assertEqual(page.parent, Path("/data/runs/20261024T120000Z-ab12/pages"))
        self.assertNotIn("example", str(page))

    def test_run_dir_rejects_path_tricks(self) -> None:
        for run_id in ("", "..", "../x", "a/b", ".hidden", "a b"):
            with self.subTest(run_id=run_id), self.assertRaises(ValueError):
                config.run_dir(Path("/data"), run_id)

    def test_fixed_paths(self) -> None:
        data = Path("/data")
        self.assertEqual(config.state_db_path(data), Path("/data/state.sqlite3"))
        self.assertEqual(
            config.reference_cache_dir(data, "https://example.com/"),
            Path("/data/reference-cache") / config.page_key("https://example.com/"),
        )
        self.assertEqual(config.deny_list_path(Path("/c")), Path("/c/deny.txt"))
        self.assertEqual(config.private_seeds_path(Path("/c")), Path("/c/seeds.txt"))


class PrivateFileTest(TempDirTest):
    def test_content_reaches_the_disk_before_it_replaces_the_old_file(self) -> None:
        # Otherwise a crash can leave an empty file under the final name.
        path = self.write("report.md", "old")
        calls: list[str] = []
        fsync, replace = os.fsync, os.replace

        def recording_fsync(descriptor: int) -> None:
            calls.append("fsync")
            fsync(descriptor)

        def recording_replace(source: str, target: Path) -> None:
            calls.append("replace")
            replace(source, target)

        with (
            mock.patch.object(config.os, "fsync", recording_fsync),
            mock.patch.object(config.os, "replace", recording_replace),
        ):
            config.write_private_file(path, b"new")

        self.assertEqual(calls, ["fsync", "replace"])
        self.assertEqual(path.read_bytes(), b"new")
        self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)

    def test_signature_key_is_created_once_and_kept(self) -> None:
        key = config.signature_key(self.root)

        self.assertEqual(len(key), 32)
        self.assertEqual(config.signature_key(self.root), key)
        path = self.root / config.SIGNATURE_KEY_NAME
        self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)

    def test_a_damaged_signature_key_is_an_error_not_replaced(self) -> None:
        # A new key would rename every site-scoped issue signature without a word.
        path = self.root / config.SIGNATURE_KEY_NAME
        for content in (b"", b"short"):
            with self.subTest(content=content):
                path.write_bytes(content)
                with self.assertRaisesRegex(ConfigError, "damaged"):
                    config.signature_key(self.root)
                self.assertEqual(path.read_bytes(), content)


class RunLockTest(TempDirTest):
    def test_second_holder_is_refused(self) -> None:
        with config.run_lock(self.root):
            with self.assertRaises(RunLockedError) as raised, config.run_lock(self.root):
                self.fail("the lock was acquired twice")
            self.assertIn(f"pid {os.getpid()}", str(raised.exception))
        # Released on exit.
        with config.run_lock(self.root):
            pass

    def test_lock_file_is_private(self) -> None:
        with config.run_lock(self.root):
            pass
        self.assertEqual(stat.S_IMODE((self.root / config.LOCK_NAME).stat().st_mode) & 0o077, 0)


class HisterSettingsTest(TempDirTest):
    TOKEN = "s3cr3t-T0ken-value"

    def load(
        self, text: str | None, env: dict[str, str] | None = None, mode: int = 0o600
    ) -> tuple[HisterSettings | None, str]:
        path = self.root / "hister.env"
        if text is not None:
            path = self.write("hister.env", text, mode)
        warnings = io.StringIO()
        settings = config.load_hister_settings(path, environ=env or {}, warnings=warnings)
        return settings, warnings.getvalue()

    def test_parses_comments_quotes_and_whitespace(self) -> None:
        text = (
            "# Hister for the site check\n"
            "\n"
            f"  HISTER_URL = 'https://hister.example.net/'  \n"
            "   # another comment\n"
            f'HISTER_TOKEN="{self.TOKEN}"\n'
        )
        settings, warnings = self.load(text)
        self.assertEqual(settings, HisterSettings("https://hister.example.net/", self.TOKEN))
        self.assertEqual(warnings, "")

    def test_unquoted_values_keep_inner_characters(self) -> None:
        settings, _ = self.load("HISTER_URL=https://h.example.net/#x\nHISTER_TOKEN=a=b'c\n")
        self.assertEqual(settings, HisterSettings("https://h.example.net/#x", "a=b'c"))

    def test_mismatched_quotes_are_kept(self) -> None:
        settings, _ = self.load("HISTER_URL=https://h.example.net\nHISTER_TOKEN=\"abc'\n")
        self.assertIsNotNone(settings)
        self.assertEqual(settings.token, "\"abc'")

    def test_file_is_never_executed(self) -> None:
        marker = self.root / "executed"
        text = (
            f"HISTER_URL=https://h.example.net\nHISTER_TOKEN=$(touch {marker})\ntouch {marker}=1\n"
        )
        settings, _ = self.load(text)
        self.assertEqual(settings.token, f"$(touch {marker})")
        self.assertFalse(marker.exists())

    def test_missing_file_and_environment_mean_unconfigured(self) -> None:
        settings, warnings = self.load(None)
        self.assertIsNone(settings)
        self.assertEqual(warnings, "")

    def test_both_values_are_required(self) -> None:
        for text in (
            "HISTER_URL=https://h.example.net\n",
            f"HISTER_TOKEN={self.TOKEN}\n",
            f"HISTER_URL=\nHISTER_TOKEN={self.TOKEN}\n",
            "HISTER_URL=https://h.example.net\nHISTER_TOKEN=''\n",
            'HISTER_URL=https://h.example.net\nHISTER_TOKEN="  "\n',
        ):
            with self.subTest(text=text):
                self.assertIsNone(self.load(text)[0])

    def test_environment_overrides_the_file(self) -> None:
        text = f"HISTER_URL=https://file.example.net\nHISTER_TOKEN={self.TOKEN}\n"
        env = {"HISTER_URL": "https://env.example.net", "HISTER_TOKEN": "env-token"}
        settings, _ = self.load(text, env)
        self.assertEqual(settings, HisterSettings("https://env.example.net", "env-token"))

    def test_environment_alone_is_enough(self) -> None:
        settings, _ = self.load(
            None, {"HISTER_URL": "https://env.example.net", "HISTER_TOKEN": "t"}
        )
        self.assertEqual(settings, HisterSettings("https://env.example.net", "t"))

    def test_empty_environment_value_does_not_override(self) -> None:
        text = f"HISTER_URL=https://file.example.net\nHISTER_TOKEN={self.TOKEN}\n"
        settings, _ = self.load(text, {"HISTER_TOKEN": ""})
        self.assertEqual(settings.token, self.TOKEN)

    def test_default_path_comes_from_the_config_dir(self) -> None:
        self.write("hister.env", f"HISTER_URL=https://h.example.net\nHISTER_TOKEN={self.TOKEN}\n")
        env = {config.CONFIG_DIR_ENV: str(self.root)}
        settings = config.load_hister_settings(environ=env, warnings=io.StringIO())
        self.assertEqual(settings, HisterSettings("https://h.example.net", self.TOKEN))

    def test_repr_redacts_the_token(self) -> None:
        settings = HisterSettings("https://h.example.net", self.TOKEN)
        for text in (repr(settings), str(settings), f"{settings}", repr([settings])):
            with self.subTest(text=text):
                self.assertNotIn(self.TOKEN, text)
                self.assertIn("redacted", text)

    def test_shared_file_warns(self) -> None:
        text = f"HISTER_URL=https://h.example.net\nHISTER_TOKEN={self.TOKEN}\n"
        for mode in (0o640, 0o604, 0o660):
            with self.subTest(mode=oct(mode)):
                settings, warnings = self.load(text, mode=mode)
                self.assertIsNotNone(settings)
                self.assertIn("chmod 600", warnings)
                self.assertNotIn(self.TOKEN, warnings)

    def test_malformed_line_error_hides_the_line(self) -> None:
        for text in (f"{self.TOKEN}\n", f"=value-{self.TOKEN}\n"):
            with self.subTest(text=text):
                with self.assertRaises(ConfigError) as raised:
                    self.load(text)
                self.assertNotIn(self.TOKEN, str(raised.exception))
                self.assertIn(":1:", str(raised.exception))

    def test_unknown_key_warning_hides_the_key(self) -> None:
        # A token pasted on its own line can look like KEY=VALUE when it ends in "=".
        settings, warnings = self.load(f"HISTER_URL=https://h.example.net\nAbCdEf{self.TOKEN}==\n")
        self.assertIsNone(settings)
        self.assertIn(":2:", warnings)
        self.assertNotIn(self.TOKEN, warnings)

    def test_duplicate_key_is_an_error(self) -> None:
        with self.assertRaises(ConfigError) as raised:
            self.load(f"HISTER_TOKEN={self.TOKEN}\nHISTER_TOKEN=other\n")
        self.assertNotIn(self.TOKEN, str(raised.exception))

    def test_invalid_utf8_is_an_error(self) -> None:
        path = self.root / "hister.env"
        path.write_bytes(b"HISTER_TOKEN=\xff\xfe\n")
        os.chmod(path, 0o600)
        with self.assertRaises(ConfigError):
            config.load_hister_settings(path, environ={}, warnings=io.StringIO())


class EnginePrefsTest(unittest.TestCase):
    def test_engine_prefs_match_the_android_build(self) -> None:
        self.assertTrue(ENGINE_SOURCE.is_file(), f"{ENGINE_SOURCE} not found; did the app move it?")
        source = _LINE_COMMENT.sub("", ENGINE_SOURCE.read_text(encoding="utf-8"))

        found = {
            match["name"]: match["value"] == "true" for match in _BOOL_PREF_CALL.finditer(source)
        }
        self.assertEqual(
            len(_ANY_PREF_CALL.findall(source)),
            len(found),
            "engine.rs sets a non-Bool preference; teach ENGINE_PREFS and the runners about it",
        )
        self.assertEqual(found, dict(config.ENGINE_PREFS))

    def test_pref_pattern_tolerates_rustfmt_wrapping(self) -> None:
        wrapped = 'preferences.set_value(\n    "some_pref",\n    PrefValue::Bool(false),\n);'
        match = _BOOL_PREF_CALL.search(wrapped)
        self.assertIsNotNone(match)
        self.assertEqual((match["name"], match["value"]), ("some_pref", "false"))

    def test_engine_prefs_are_read_only(self) -> None:
        with self.assertRaises(TypeError):
            config.ENGINE_PREFS["viewport_meta_enabled"] = False  # type: ignore[index]


class UserAgentTest(TempDirTest):
    def test_user_agent_format(self) -> None:
        self.assertEqual(
            config.user_agent("0.6.0"),
            "Mozilla/5.0 (Android 10; Mobile; rv:153.0) Servo/0.6.0 Firefox/153.0",
        )

    def test_user_agent_rejects_odd_versions(self) -> None:
        for version in ("", "0.6", "0.6.0\r\nX-Injected: 1", "latest"):
            with self.subTest(version=version), self.assertRaises(ValueError):
                config.user_agent(version)

    def test_servo_version_from_workspace(self) -> None:
        self.write(
            "Cargo.toml",
            '[workspace]\nmembers = ["a"]\n\n'
            '[workspace.package]\nversion = "0.6.0"\nedition = "2024"\n\n'
            '[workspace.dependencies]\nserde = { version = "1.0.1" }\n',
        )
        self.assertEqual(config.servo_version(self.root), "0.6.0")
        self.assertIn("Servo/0.6.0 ", config.user_agent(config.servo_version(self.root)))

    def test_servo_version_from_package(self) -> None:
        self.write("Cargo.toml", '[package]\nname = "servo"\nversion = "1.2.3-dev"\n')
        self.assertEqual(config.servo_version(self.root), "1.2.3-dev")

    def test_workspace_version_wins_over_inherited_package_version(self) -> None:
        self.write(
            "Cargo.toml",
            '[package]\nname = "servo"\nversion.workspace = true\n\n'
            '[workspace.package]\nversion = "0.7.0"\n',
        )
        self.assertEqual(config.servo_version(self.root), "0.7.0")

    def test_servo_version_errors(self) -> None:
        cases = (
            ("missing", None),
            ("no version", '[package]\nname = "servo"\n'),
            ("bad toml", "[package\n"),
            ("bad version", '[package]\nversion = "one"\n'),
        )
        for name, text in cases:
            with self.subTest(name=name):
                manifest = self.root / "Cargo.toml"
                manifest.unlink(missing_ok=True)
                if text is not None:
                    self.write("Cargo.toml", text)
                with self.assertRaises(ConfigError):
                    config.servo_version(self.root)

    def test_viewport_matches_spec(self) -> None:
        self.assertEqual(
            (config.VIEWPORT_WIDTH, config.VIEWPORT_HEIGHT, config.DEVICE_PIXEL_RATIO),
            (412, 915, 1),
        )


class ServoBinaryTest(TempDirTest):
    def test_environment_wins_even_if_missing(self) -> None:
        env = {"HOME": str(self.root), config.SERVO_BINARY_ENV: "~/elsewhere/servoshell"}
        self.assertEqual(config.default_servo_binary(env), self.root / "elsewhere" / "servoshell")

    def test_default_checkout_when_built(self) -> None:
        env = {"HOME": str(self.root)}
        self.assertIsNone(config.default_servo_binary(env))

        binary = self.root / "Documents/GitHub/servo-sitecheck/target/medium/servoshell"
        binary.parent.mkdir(parents=True)
        binary.write_text("")
        self.assertEqual(config.default_servo_binary(env), binary)

    def test_servo_checkout_of_binary(self) -> None:
        binary = self.root / "servo" / "target" / "medium" / "servoshell"
        binary.parent.mkdir(parents=True)
        binary.write_text("")
        self.assertEqual(config.servo_checkout(binary), (self.root / "servo").resolve())

    def test_servo_checkout_requires_a_target_dir(self) -> None:
        with self.assertRaises(ConfigError):
            config.servo_checkout(self.root / "bin" / "servoshell")


class ListFileTest(TempDirTest):
    def test_deny_list(self) -> None:
        path = self.write(
            "deny.txt",
            "# private hosts\n\n.Bank.Example.com.\n"
            "https://Mail.Example.org/inbox\n  intranet.example.net  \n",
        )
        self.assertEqual(
            config.load_deny_list(path),
            ("bank.example.com", "https://mail.example.org/inbox", "intranet.example.net"),
        )

    def test_deny_list_rejects_bad_entries(self) -> None:
        path = self.write("deny.txt", "example.com\nftp://example.org/\n")
        with self.assertRaises(ConfigError) as raised:
            config.load_deny_list(path)
        self.assertIn(":2:", str(raised.exception))

    def test_missing_lists_are_empty(self) -> None:
        self.assertEqual(config.load_deny_list(self.root / "deny.txt"), ())
        self.assertEqual(config.load_seed_urls(self.root / "seeds.txt"), ())

    def test_seed_urls(self) -> None:
        path = self.write(
            "seeds.txt", "# seeds\nhttps://example.com/\n\n  https://example.org/a  \n"
        )
        self.assertEqual(
            config.load_seed_urls(path), ("https://example.com/", "https://example.org/a")
        )

    def test_bundled_seeds_path(self) -> None:
        self.assertEqual(
            config.BUNDLED_SEEDS_PATH, REPO_ROOT / "testing" / "sitecheck" / "seeds.txt"
        )


if __name__ == "__main__":
    unittest.main()
