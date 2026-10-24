"""Tests for sitecheck.leakcheck: term matching, and scans of a temporary git repository."""

from __future__ import annotations

import os
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from sitecheck import leakcheck
from sitecheck.leakcheck import Leak, TermKind

# Synthetic stand-ins for pages from a history.
HOSTS = ("www.private-blog.net", "news.secret-paper.co.uk", "old-site.org", "nas", "192.168.1.20")
SITES = ("private-blog.net", "secret-paper.co.uk", "old-site.org", "nas", "192.168.1.20")
URLS = (
    "https://en.wikipedia.org/wiki/Private_Topic",
    "https://www.private-blog.net/",
    "file:///Users/someone/fixture.html",
)

# Commands below must not depend on the developer's git configuration (signing, hooks).
_GIT_ENV = {
    "GIT_CONFIG_GLOBAL": os.devnull,
    "GIT_CONFIG_NOSYSTEM": "1",
    "GIT_AUTHOR_NAME": "Test",
    "GIT_AUTHOR_EMAIL": "test@example.com",
    "GIT_COMMITTER_NAME": "Test",
    "GIT_COMMITTER_EMAIL": "test@example.com",
}


def terms() -> leakcheck.Inventory:
    return leakcheck.inventory(HOSTS, SITES, URLS)


def found(text: str) -> list[tuple[str, TermKind]]:
    return [(leak.term, leak.kind) for leak in leakcheck.scan_text("t", text, terms())]


class InventoryTest(unittest.TestCase):
    def test_allowlisted_single_label_and_private_names_are_not_terms(self) -> None:
        inventory = leakcheck.inventory(
            [
                "en.wikipedia.org",
                "docs.github.com",
                "nas",
                "10.0.0.8",
                "8.8.4.4",
                "Shop.Example-Store.NET",
            ],
            ["wikipedia.org", "github.com", "example-store.net"],
            [],
        )
        self.assertEqual(
            dict(inventory.names),
            {
                "8.8.4.4": TermKind.HOST,
                "shop.example-store.net": TermKind.HOST,
                "example-store.net": TermKind.SITE,
            },
        )

    def test_a_root_url_with_a_query_matches_with_or_without_the_slash(self) -> None:
        inventory = leakcheck.inventory([], [], ["https://github.com/?tab=stars"])
        for text in ("see github.com/?tab=stars", "see https://github.com?tab=stars"):
            with self.subTest(text=text):
                self.assertEqual(len(leakcheck.scan_text("t", text, inventory)), 1)

    def test_urls_need_a_path_and_a_web_scheme(self) -> None:
        self.assertEqual(
            dict(terms().urls_by_host),
            {"en.wikipedia.org": ("en.wikipedia.org/wiki/private_topic",)},
        )

    def test_extra_allowed_names(self) -> None:
        inventory = leakcheck.inventory(["www.bbc.co.uk"], ["bbc.co.uk"], [], allowed={"bbc.co.uk"})
        self.assertTrue(inventory.empty)

    def test_public_references_the_engine_work_cites_are_not_terms(self) -> None:
        inventory = leakcheck.inventory(
            ["drafts.csswg.org", "bugzilla.mozilla.org"],
            ["csswg.org", "mozilla.org"],
            [
                "https://github.com/servo/servo/pull/12345",
                "https://developer.mozilla.org/en-US/docs/Web/API/Element/animate",
                "https://drafts.csswg.org/css-flexbox-1/#algo-main-item",
            ],
        )
        self.assertTrue(inventory.empty, inventory)


class UrlSpellingTest(unittest.TestCase):
    """A stored URL is also found when written without its query or shortened."""

    def scan(self, stored: str, text: str) -> list[str]:
        inventory = leakcheck.inventory([], [], [stored])
        return [leak.term for leak in leakcheck.scan_text("t", text, inventory)]

    def test_leading_path_segments_of_a_stored_url_match(self) -> None:
        stored = "https://github.com/acme-corp/internal-dashboard/pull/42"
        self.assertEqual(
            self.scan(stored, "Reduced from github.com/acme-corp/internal-dashboard"),
            ["github.com/acme-corp/internal-dashboard"],
        )
        self.assertEqual(self.scan(stored, "see github.com/acme-corp/internal-dashboard-v2"), [])

    def test_a_stored_url_matches_without_its_query(self) -> None:
        for stored, text in (
            (
                "https://github.com/acme-private/secret-repo/issues/12?tab=readme",
                "github.com/acme-private/secret-repo/issues/12",
            ),
            (
                "https://en.wikipedia.org/wiki/Some_Article?oldid=5",
                "en.wikipedia.org/wiki/Some_Article",
            ),
        ):
            with self.subTest(stored=stored):
                self.assertTrue(self.scan(stored, f"Seen on {text}."))

    def test_a_single_segment_is_not_shortened(self) -> None:
        # "example.com/item" would match every item page anyone cites.
        self.assertEqual(self.scan("https://example.com/item?id=5", "example.com/item?id=6"), [])

    def test_internationalized_names_match_in_both_spellings(self) -> None:
        punycode = leakcheck.inventory(
            ["www.xn--zrich-beispiel-gsb.ch"], ["xn--zrich-beispiel-gsb.ch"], []
        )
        unicode = leakcheck.inventory(["www.zürich-beispiel.ch"], ["zürich-beispiel.ch"], [])
        for inventory, text in (
            (punycode, "Fixes the menu on www.zürich-beispiel.ch"),
            (unicode, "Fixes the menu on www.xn--zrich-beispiel-gsb.ch"),
            (unicode, "Fixes the menu on WWW.ZÜRICH-BEISPIEL.CH"),
        ):
            with self.subTest(text=text):
                self.assertTrue(leakcheck.scan_text("t", text, inventory))

    def test_internationalized_urls_match_in_both_spellings(self) -> None:
        inventory = leakcheck.inventory([], [], ["https://www.xn--zrich-beispiel-gsb.ch/menu/x"])
        leaks = leakcheck.scan_text("t", "www.zürich-beispiel.ch/menu/x", inventory)
        self.assertEqual([leak.kind for leak in leaks], [TermKind.URL])


class ScanTextTest(unittest.TestCase):
    def test_matches_whole_domains_in_any_case(self) -> None:
        cases = {
            "see www.private-blog.net for details": [
                ("www.private-blog.net", TermKind.HOST),
                ("private-blog.net", TermKind.SITE),
            ],
            "PRIVATE-BLOG.NET.": [("private-blog.net", TermKind.SITE)],
            "https://private-blog.net/x": [("private-blog.net", TermKind.SITE)],
            "tests/private-blog.net.html": [("private-blog.net", TermKind.SITE)],
            "a reduced case from sport.secret-paper.co.uk": [("secret-paper.co.uk", TermKind.SITE)],
            "fix-private-blog.net-menu": [("private-blog.net", TermKind.SITE)],
            "secret-paper.co.ukraine": [],
            "myprivate-blog.net": [],
            "private-blog.network": [],
            "old-site.organic": [],
            "the nas and 192.168.1.20": [],
            "example.com and en.wikipedia.org/wiki/Other_Topic": [],
        }
        for text, expected in cases.items():
            with self.subTest(text=text):
                self.assertEqual(found(text), expected)

    def test_matches_stored_urls_on_allowlisted_hosts(self) -> None:
        self.assertEqual(
            found("Seen on https://en.wikipedia.org/wiki/Private_Topic."),
            [("en.wikipedia.org/wiki/private_topic", TermKind.URL)],
        )
        self.assertEqual(found("en.wikipedia.org/wiki/Private_Topic_2"), [])

    def test_reports_line_numbers(self) -> None:
        leaks = leakcheck.scan_text("pr.md", "title\n\nfixes old-site.org\n", terms())
        self.assertEqual(leaks, [Leak("pr.md", 3, "old-site.org", TermKind.HOST)])
        self.assertEqual(str(leaks[0]), "pr.md:3: matched host old-site.org")


class UpstreamContactTest(unittest.TestCase):
    """Text that would reach the upstream project's issues or people when published."""

    def contact(self, text: str, *, mentions: bool = True) -> list[tuple[str, TermKind]]:
        return [
            (leak.term, leak.kind)
            for leak in leakcheck.scan_upstream_contact("t", text, mentions=mentions)
        ]

    def test_cross_repository_references_to_the_upstream_project(self) -> None:
        self.assertEqual(
            self.contact(
                "Fixes https://github.com/servo/servo/issues/1234\n"
                "Like servo/servo#4567 and servo/stylo#12, and GitHub.com/Servo/Servo/pull/9."
            ),
            [
                ("github.com/servo/servo/issues/1234", TermKind.UPSTREAM_REFERENCE),
                ("servo/servo#4567", TermKind.UPSTREAM_REFERENCE),
                ("servo/stylo#12", TermKind.UPSTREAM_REFERENCE),
                ("github.com/servo/servo/pull/9", TermKind.UPSTREAM_REFERENCE),
            ],
        )

    def test_local_references_and_commit_hashes_are_fine(self) -> None:
        text = "Backport of upstream 040e2840d4c (#49189); see L-K-M/servo#3."
        self.assertEqual(self.contact(text), [])

    def test_mentions_outside_code(self) -> None:
        self.assertEqual(
            self.contact("Thanks @mrobinson, cc @Some-Body."),
            [("@mrobinson", TermKind.MENTION), ("@Some-Body", TermKind.MENTION)],
        )

    def test_css_at_rules_addresses_and_code_are_not_mentions(self) -> None:
        text = (
            "A test with @media (min-width: 1px), @font-face and @supports;\n"
            "mail noreply@anthropic.com; `@inline` code.\n"
            "```css\n@someone { }\n```\n"
        )
        self.assertEqual(self.contact(text), [])

    def test_mentions_are_ignored_where_they_notify_nobody(self) -> None:
        self.assertEqual(self.contact("Thanks @mrobinson", mentions=False), [])

    def test_reports_line_numbers(self) -> None:
        leaks = leakcheck.scan_upstream_contact("body.md", "one\ncc @someone\n", mentions=True)
        self.assertEqual([str(leak) for leak in leaks], ["body.md:2: matched mention @someone"])


class GitRangeTest(unittest.TestCase):
    def setUp(self) -> None:
        patcher = mock.patch.dict(os.environ, _GIT_ENV)
        patcher.start()
        self.addCleanup(patcher.stop)
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.repo = Path(temporary.name)
        self.git("init", "--quiet", "--initial-branch=main")
        (self.repo / "README.md").write_text("Mentions old-site.org already.\n")
        self.commit("Initial commit")
        self.base = self.git("rev-parse", "HEAD").strip()

    def git(self, *arguments: str) -> str:
        return subprocess.run(
            ["git", "-C", str(self.repo), "-c", "commit.gpgsign=false", *arguments],
            check=True,
            capture_output=True,
            text=True,
        ).stdout

    def commit(self, message: str) -> None:
        self.git("add", "--all")
        self.git("commit", "--quiet", "--no-verify", "-m", message)

    def test_finds_terms_in_added_lines_file_names_and_messages(self) -> None:
        (self.repo / "notes.txt").write_text(
            "line one\nexample.com is fine\nbroken on www.private-blog.net\n"
        )
        (self.repo / "secret-paper.co.uk.html").write_text("<p>reduced case</p>\n")
        (self.repo / "README.md").write_text(
            "Mentions old-site.org already.\nAnd old-site.org again.\n"
        )
        self.commit("Fix layout\n\nSeen on en.wikipedia.org/wiki/Private_Topic")

        leaks = leakcheck.scan_git_range(self.repo, f"{self.base}..HEAD", terms())
        commit = self.git("rev-parse", "--short=12", "HEAD").strip()
        self.assertEqual(
            sorted(map(str, leaks)),
            sorted(
                [
                    f"notes.txt:3 (commit {commit}): matched host www.private-blog.net",
                    f"notes.txt:3 (commit {commit}): matched site private-blog.net",
                    f"secret-paper.co.uk.html (commit {commit}): matched site secret-paper.co.uk",
                    f"commit {commit}:3: matched url en.wikipedia.org/wiki/private_topic",
                ]
            ),
        )  # old-site.org was already in the repository at the base.

    def test_finds_terms_added_and_removed_again_within_the_range(self) -> None:
        # Pushing the branch publishes every commit, not just the net change.
        (self.repo / "notes.txt").write_text("broken on www.private-blog.net\n")
        self.commit("Add notes")
        added = self.git("rev-parse", "--short=12", "HEAD").strip()
        (self.repo / "notes.txt").write_text("a synthetic reduced case\n")
        self.commit("Rewrite notes")

        leaks = leakcheck.scan_git_range(self.repo, f"{self.base}..HEAD", terms())
        self.assertIn(
            f"notes.txt:1 (commit {added}): matched site private-blog.net", map(str, leaks)
        )

    def test_file_names_are_not_read_as_pathspecs(self) -> None:
        name = ":(exclude)*.txt"  # As a pathspec, it would exclude itself.
        (self.repo / name).write_text("broken on www.private-blog.net\n")
        self.git("--literal-pathspecs", "add", name)
        self.commit("Add notes")

        leaks = leakcheck.scan_git_range(self.repo, f"{self.base}..HEAD", terms())
        self.assertIn("private-blog.net", {leak.term for leak in leaks if leak.line == 1})

    def test_binary_file_names_are_scanned(self) -> None:
        (self.repo / "private-blog.net.png").write_bytes(b"\x89PNG\r\n\x1a\n\x00\x00")
        self.commit("Add screenshot")
        commit = self.git("rev-parse", "--short=12", "HEAD").strip()
        leaks = leakcheck.scan_git_range(self.repo, f"{self.base}..HEAD", terms())
        self.assertEqual(
            [str(leak) for leak in leaks],
            [f"private-blog.net.png (commit {commit}): matched site private-blog.net"],
        )

    def test_commits_reachable_from_an_excluded_ref_are_skipped(self) -> None:
        # An upstream merge brings public commits that are not ours to scan.
        self.git("checkout", "--quiet", "-b", "upstream")
        (self.repo / "upstream.txt").write_text("upstream mentions private-blog.net\n")
        self.commit("Upstream change")
        self.git("checkout", "--quiet", "main")
        self.git("merge", "--quiet", "--no-ff", "--no-edit", "upstream")

        everything = leakcheck.scan_git_range(self.repo, f"{self.base}..HEAD", terms())
        self.assertEqual({leak.term for leak in everything}, {"private-blog.net"})
        ours = leakcheck.scan_git_range(
            self.repo, f"{self.base}..HEAD", terms(), exclude_refs=["upstream"]
        )
        self.assertEqual(ours, [])
        with self.assertRaises(leakcheck.LeakcheckError):
            leakcheck.scan_git_range(
                self.repo, f"{self.base}..HEAD", terms(), exclude_refs=["--all"]
            )

    def test_finds_terms_in_the_branch_name(self) -> None:
        self.git("checkout", "--quiet", "-b", "fix-private-blog.net-menu")
        (self.repo / "fix.txt").write_text("a synthetic reduced case\n")
        self.commit("Fix menu")
        leaks = leakcheck.scan_git_range(self.repo, f"{self.base}..HEAD", terms())
        self.assertEqual([str(leak) for leak in leaks], ["branch: matched site private-blog.net"])

    def test_a_clean_range(self) -> None:
        (self.repo / "fix.txt").write_text("a synthetic reduced case on example.com\n")
        self.commit("Fix menu")
        self.assertEqual(leakcheck.scan_git_range(self.repo, f"{self.base}..HEAD", terms()), [])

    def test_finds_upstream_references_in_messages_but_not_files(self) -> None:
        # GitHub links a commit message to the issue it names, on the upstream side.
        (self.repo / "fix.rs").write_text("// https://github.com/servo/servo/issues/1\n")
        self.commit("Fix menu\n\nFixes https://github.com/servo/servo/issues/1234")
        commit = self.git("rev-parse", "--short=12", "HEAD").strip()
        leaks = leakcheck.scan_git_range(self.repo, f"{self.base}..HEAD", terms())
        self.assertEqual(
            [str(leak) for leak in leaks],
            [f"commit {commit}:3: matched upstream reference github.com/servo/servo/issues/1234"],
        )

    def test_terms_at_the_base_still_count_in_messages_and_branch_names(self) -> None:
        # old-site.org is in README.md at the base; that does not make naming it in
        # a commit message or a branch name harmless.
        self.git("checkout", "--quiet", "-b", "fix-old-site.org-menu")
        (self.repo / "fix.txt").write_text("a synthetic reduced case\n")
        self.commit("Fix the menu seen on old-site.org")
        commit = self.git("rev-parse", "--short=12", "HEAD").strip()

        leaks = leakcheck.scan_git_range(self.repo, f"{self.base}..HEAD", terms())

        self.assertEqual(
            sorted(map(str, leaks)),
            [
                "branch: matched host old-site.org",
                f"commit {commit}:1: matched host old-site.org",
            ],
        )

    def test_terms_at_the_base_count_in_other_files(self) -> None:
        (self.repo / "new-test.html").write_text("<a href=https://old-site.org/>x</a>\n")
        (self.repo / "README.md").write_text("Mentions old-site.org already.\nold-site.org\n")
        self.commit("Add a test")
        commit = self.git("rev-parse", "--short=12", "HEAD").strip()

        leaks = leakcheck.scan_git_range(self.repo, f"{self.base}..HEAD", terms())

        self.assertEqual(
            [str(leak) for leak in leaks],
            [f"new-test.html:1 (commit {commit}): matched host old-site.org"],
        )

    def test_a_conflict_resolution_in_a_merge_is_scanned(self) -> None:
        (self.repo / "shared.txt").write_text("one\ntwo\nthree\n")
        self.commit("Add shared")
        base = self.git("rev-parse", "HEAD").strip()
        self.git("checkout", "--quiet", "-b", "upstream")
        (self.repo / "shared.txt").write_text("one\nupstream two\nthree\n")
        self.commit("Upstream change")
        self.git("checkout", "--quiet", "main")
        (self.repo / "shared.txt").write_text("one\nour two\nthree\n")
        self.commit("Our change")
        subprocess.run(
            ["git", "-C", str(self.repo), "merge", "--quiet", "--no-edit", "upstream"],
            capture_output=True,
            check=False,
        )
        (self.repo / "shared.txt").write_text("one\nsee www.private-blog.net for repro\nthree\n")
        self.commit("Merge upstream")
        merge = self.git("rev-parse", "--short=12", "HEAD").strip()

        leaks = leakcheck.scan_git_range(
            self.repo, f"{base}..HEAD", terms(), exclude_refs=["upstream"]
        )

        self.assertEqual(
            sorted(map(str, leaks)),
            [
                f"shared.txt:2 (commit {merge}): matched host www.private-blog.net",
                f"shared.txt:2 (commit {merge}): matched site private-blog.net",
            ],
        )

    def test_invalid_ranges(self) -> None:
        for git_range in ("HEAD", "a...b", f"{self.base}..nope", "-x..HEAD"):
            with self.subTest(git_range=git_range), self.assertRaises(leakcheck.LeakcheckError):
                leakcheck.scan_git_range(self.repo, git_range, terms())

    def test_unreadable_text_file(self) -> None:
        with self.assertRaises(leakcheck.LeakcheckError):
            leakcheck.scan_files([self.repo / "missing.md"], terms())


if __name__ == "__main__":
    unittest.main()
