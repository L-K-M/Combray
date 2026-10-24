"""Disposable engine sources recover to the pin while build caches stay reusable."""

import os
from pathlib import Path
import shlex
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class FetchServoTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="combray source ")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.repo = self.root / "remote"
        self.app = self.root / "app"
        self.scripts = self.app / "scripts/m0"
        self.scripts.mkdir(parents=True)
        self.source = self.app / "servo-checkout/servo"
        self.environment = os.environ | {
            "GIT_AUTHOR_NAME": "Fixture", "GIT_AUTHOR_EMAIL": "fixture@localhost",
            "GIT_COMMITTER_NAME": "Fixture", "GIT_COMMITTER_EMAIL": "fixture@localhost",
            "ANDROID_NDK_ROOT": str(self.root / "absent-ndk"),
        }
        self.git("init", "-q", str(self.repo))
        (self.repo / ".gitignore").write_text("/.servo/\n/.venv/\n/target/\n")
        mach = self.repo / "mach"
        mach.write_text(
            '#!/bin/sh\nset -eu\n'
            '[ "${COMBRAY_TEST_PREPARE_FAIL:-}" != yes ] || exit 1\n'
            'mkdir -p .servo .venv/bin\n'
            ': > .venv/bin/python\nchmod +x .venv/bin/python\n'
            'printf "prepared\\n" >> .servo/preparations\n'
        )
        mach.chmod(0o755)
        self.upstream = self.commit("upstream")
        self.pinned = self.commit("downstream")
        self.tip = self.commit("moving branch")
        shutil.copy2(ROOT / "scripts/m0/fetch-servo.sh", self.scripts)
        self.write_pin(self.pinned)

    def git(self, *arguments, directory=None):
        return subprocess.run(
            ["git", *arguments], cwd=directory, env=self.environment,
            check=True, text=True, capture_output=True,
        ).stdout.strip()

    def commit(self, value):
        (self.repo / "identity").write_text(value)
        self.git("add", ".", directory=self.repo)
        self.git("commit", "-qm", value, directory=self.repo)
        return self.git("rev-parse", "HEAD", directory=self.repo)

    def write_pin(self, revision):
        (self.scripts / "servo-pin.sh").write_text(
            f"export COMBRAY_ROOT={shlex.quote(str(self.app))}\n"
            'export COMBRAY_WORK="$COMBRAY_ROOT/servo-checkout"\n'
            'export COMBRAY_SERVO_SRC="$COMBRAY_WORK/servo"\n'
            f"export COMBRAY_SERVO_REPOSITORY={shlex.quote(str(self.repo))}\n"
            f"export COMBRAY_SERVO_GIT_REVISION={shlex.quote(revision)}\n"
            f"export COMBRAY_SERVO_UPSTREAM_REVISION={shlex.quote(self.upstream)}\n"
        )

    def fetch(self, *arguments, extra_environment=None):
        return subprocess.run(
            ["bash", str(self.scripts / "fetch-servo.sh"), *arguments], cwd=self.app,
            env=self.environment | (extra_environment or {}),
            text=True, capture_output=True, check=False,
        )

    def assert_success(self, result):
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_fetch_uses_the_exact_pin_without_an_android_sdk(self):
        self.assert_success(self.fetch())
        self.assertEqual(self.git("rev-parse", "HEAD", directory=self.source), self.pinned)
        self.assertEqual((self.source / "identity").read_text(), "downstream")

    def test_checkout_is_announced_before_git_finishes(self):
        template = self.root / "git-template"
        hooks = template / "hooks"
        hooks.mkdir(parents=True)
        hook = hooks / "post-checkout"
        hook.write_text('#!/bin/sh\nprintf "fixture checkout finished\\n"\n')
        hook.chmod(0o755)

        result = subprocess.run(
            ["bash", str(self.scripts / "fetch-servo.sh")], cwd=self.app,
            env=self.environment | {"GIT_TEMPLATE_DIR": str(template)},
            text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=False,
        )
        self.assertEqual(result.returncode, 0, result.stdout)
        announcement = f"==> checking out Servo {self.pinned}"
        self.assertIn(announcement, result.stdout)
        self.assertLess(result.stdout.index(announcement), result.stdout.index("fixture checkout finished"))

    def test_partial_first_checkout_recovers_without_fetching_again(self):
        self.source.mkdir(parents=True)
        self.git("init", "-q", str(self.source))
        self.git("fetch", "--no-tags", "--depth=1", str(self.repo), self.pinned, directory=self.source)
        partial = self.source / "identity"
        partial.write_text("partial checkout")
        leftover = self.source / "leftover.rs"
        leftover.write_text("incomplete source")
        self.repo.rename(self.root / "remote-offline")

        result = self.fetch()
        self.assert_success(result)
        self.assertEqual(partial.read_text(), "downstream")
        self.assertFalse(leftover.exists())
        self.assertEqual(self.git("rev-parse", "HEAD", directory=self.source), self.pinned)
        self.assertTrue((self.source / ".git/combray-prepared").exists())

    def test_prepared_sources_are_reusable_offline(self):
        self.assert_success(self.fetch())
        source_mtime = (self.source / "identity").stat().st_mtime_ns
        self.repo.rename(self.root / "remote-offline")
        self.assert_success(self.fetch())
        self.assertEqual((self.source / ".servo/preparations").read_text(), "prepared\n")
        self.assertEqual((self.source / "identity").stat().st_mtime_ns, source_mtime)

    def test_mode_switches_preserve_the_build_cache(self):
        self.assert_success(self.fetch())
        target = self.source / "target"
        target.mkdir()
        (target / "keep").write_text("compiled")
        self.assert_success(self.fetch("upstream"))
        self.assertEqual((self.source / "identity").read_text(), "upstream")
        self.assert_success(self.fetch())
        self.assertEqual((self.source / "identity").read_text(), "downstream")
        self.assertEqual((target / "keep").read_text(), "compiled")

    def test_tracked_edits_are_replaced_with_the_pinned_source(self):
        self.assert_success(self.fetch())
        (self.source / "identity").write_text("local work")
        self.assert_success(self.fetch())
        self.assertEqual((self.source / "identity").read_text(), "downstream")

    def test_untracked_sources_are_removed_but_build_caches_are_reused(self):
        self.assert_success(self.fetch())
        new_source = self.source / "unfinished.rs"
        new_source.write_text("local work")
        target = self.source / "target"
        target.mkdir()
        (target / "keep").write_text("compiled")
        self.assert_success(self.fetch())
        self.assertFalse(new_source.exists())
        self.assertEqual((target / "keep").read_text(), "compiled")
        self.assertEqual((self.source / ".servo/preparations").read_text(), "prepared\n")

    def test_detached_local_commits_are_replaced_with_the_pin(self):
        self.assert_success(self.fetch())
        (self.source / "identity").write_text("local commit")
        self.git("commit", "-am", "local work", directory=self.source)
        self.assert_success(self.fetch())
        self.assertEqual(self.git("rev-parse", "HEAD", directory=self.source), self.pinned)
        self.assertEqual((self.source / "identity").read_text(), "downstream")

    def test_local_branch_does_not_change_the_build_input(self):
        self.assert_success(self.fetch())
        self.git("switch", "-qc", "local-work", directory=self.source)
        self.git("commit", "--allow-empty", "-qm", "local work", directory=self.source)
        self.assert_success(self.fetch("upstream"))
        self.assertEqual(self.git("rev-parse", "HEAD", directory=self.source), self.upstream)

    def test_pin_updates_accept_the_previous_prepared_commit(self):
        self.assert_success(self.fetch())
        self.write_pin(self.tip)
        self.assert_success(self.fetch())
        self.assertEqual(self.git("rev-parse", "HEAD", directory=self.source), self.tip)

    def test_a_deleted_python_environment_is_prepared_again(self):
        self.assert_success(self.fetch())
        shutil.rmtree(self.source / ".venv")
        self.assert_success(self.fetch())
        self.assertTrue(os.access(self.source / ".venv/bin/python", os.X_OK))
        self.assertEqual((self.source / ".servo/preparations").read_text(), "prepared\nprepared\n")

    def test_an_existing_non_git_tree_is_not_overwritten(self):
        self.source.mkdir(parents=True)
        (self.source / "identity").write_text("legacy work")
        result = self.fetch()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("dedicated Git checkout", result.stderr)
        self.assertEqual((self.source / "identity").read_text(), "legacy work")

    def test_failed_python_preparation_is_retried(self):
        result = self.fetch(extra_environment={"COMBRAY_TEST_PREPARE_FAIL": "yes"})
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.source / ".git/combray-prepared").exists())
        self.assert_success(self.fetch())
        self.assertEqual((self.source / ".servo/preparations").read_text(), "prepared\n")

    def test_an_unavailable_revision_does_not_change_prepared_sources(self):
        self.assert_success(self.fetch())
        self.write_pin("0" * 40)
        self.assertNotEqual(self.fetch().returncode, 0)
        self.assertEqual(self.git("rev-parse", "HEAD", directory=self.source), self.pinned)

    def test_inherited_git_environment_cannot_retarget_the_checkout(self):
        self.assert_success(self.fetch(extra_environment={
            "GIT_DIR": str(self.repo / ".git"), "GIT_WORK_TREE": str(self.repo),
            "GIT_COMMON_DIR": str(self.repo / ".git"),
            "GIT_OBJECT_DIRECTORY": str(self.repo / ".git/objects"),
            "GIT_INDEX_FILE": str(self.repo / ".git/index"),
        }))
        self.assertEqual((self.source / "identity").read_text(), "downstream")
        self.assertEqual((self.repo / "identity").read_text(), "moving branch")

    def test_git_worktree_setting_cannot_retarget_source_cleanup(self):
        self.assert_success(self.fetch())
        self.git("config", "core.worktree", str(self.repo), directory=self.source)
        self.assert_success(self.fetch())
        self.assertEqual((self.source / "identity").read_text(), "downstream")
        self.assertEqual((self.repo / "identity").read_text(), "moving branch")

    def test_a_linked_checkout_is_not_reset(self):
        self.source.parent.mkdir(parents=True)
        self.source.symlink_to(self.repo, target_is_directory=True)
        result = self.fetch()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("dedicated Git checkout", result.stderr)
        self.assertEqual((self.repo / "identity").read_text(), "moving branch")

    def test_unknown_mode_does_not_create_a_checkout(self):
        self.assertEqual(self.fetch("unknown").returncode, 2)
        self.assertFalse(self.source.exists())


if __name__ == "__main__":
    unittest.main()
