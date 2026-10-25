"""Tests for run planning: buckets, budget split, per-site cap and determinism."""

from __future__ import annotations

import random
import unittest
from collections import Counter
from datetime import UTC, datetime, timedelta
from urllib.parse import urlsplit

from sitecheck import urlpolicy
from sitecheck.selection import Bucket, Page, PlannedPage, select

NOW = datetime(2026, 10, 24, 12, 0, tzinfo=UTC)
SEED = "20261024T120000Z-ab12"


def page(
    url: str,
    *,
    add_count: int | None = 1,
    visited_days_ago: float | None = 30,
    checked_days_ago: float | None = None,
    verdict: str | None = None,
    severity: int | None = None,
    attempted_days_ago: float | None = None,
    decided: bool = False,
) -> Page:
    host = urlsplit(url).hostname or ""
    visited = (
        None
        if visited_days_ago is None
        else int((NOW - timedelta(days=visited_days_ago)).timestamp())
    )
    checked = None if checked_days_ago is None else NOW - timedelta(days=checked_days_ago)
    if verdict is not None and checked is None:
        checked = NOW - timedelta(days=1)
    return Page(
        url=url,
        site=urlpolicy.site_of(host),
        template=urlpolicy.template_of(url),
        add_count=add_count,
        hister_updated=visited,
        last_checked=checked,
        last_verdict=verdict,
        last_severity=severity,
        last_attempted=None
        if attempted_days_ago is None
        else NOW - timedelta(days=attempted_days_ago),
        decided=decided,
    )


def urls(plan: list[PlannedPage]) -> list[str]:
    return [entry.url for entry in plan]


class InputTest(unittest.TestCase):
    def test_empty_input_or_budget(self) -> None:
        self.assertEqual(select([], 10, NOW, SEED), [])
        self.assertEqual(select([page("https://a.com/")], 0, NOW, SEED), [])

    def test_invalid_arguments(self) -> None:
        pages = [page("https://a.com/")]
        with self.assertRaises(ValueError):
            select(pages, -1, NOW, SEED)
        with self.assertRaises(ValueError):
            select(pages, 1, NOW, SEED, per_site=0)
        with self.assertRaises(ValueError):
            select(pages, 1, NOW.replace(tzinfo=None), SEED)

    def test_duplicate_url_is_an_error_without_the_url(self) -> None:
        with self.assertRaises(ValueError) as raised:
            select([page("https://a.com/private"), page("https://a.com/private")], 5, NOW, SEED)
        self.assertNotIn("a.com", str(raised.exception))

    def test_unknown_verdict_is_an_error(self) -> None:
        with self.assertRaises(ValueError):
            select([page("https://a.com/", verdict="exploded")], 5, NOW, SEED)

    def test_naive_last_checked_is_an_error(self) -> None:
        naive = Page("https://a.com/", "a.com", None, 1, None, datetime(2026, 1, 1), "ok", 0)
        with self.assertRaises(ValueError):
            select([naive], 5, NOW, SEED)

    def test_fewer_pages_than_budget(self) -> None:
        plan = select([page("https://a.com/"), page("https://b.com/")], 10, NOW, SEED)
        self.assertEqual(sorted(urls(plan)), ["https://a.com/", "https://b.com/"])


class PerSiteTest(unittest.TestCase):
    def site_pages(self) -> list[Page]:
        return [
            page("https://www.news.com/", visited_days_ago=40, add_count=5),
            page("https://news.com/", visited_days_ago=1, add_count=1),
            page("https://www.news.com/about", visited_days_ago=2),
            page("https://www.news.com/article/1", visited_days_ago=9),
            page("https://www.news.com/article/2", visited_days_ago=3),
            page("https://www.news.com/article/3", visited_days_ago=5),
            page("https://www.news.com/sport", visited_days_ago=4),
            page("https://www.news.com/weather", visited_days_ago=None, add_count=None),
        ]

    def test_cap_and_candidate_order(self) -> None:
        plan = select(self.site_pages(), 10, NOW, SEED)
        # The most visited root, then one page per template, most recent first.
        self.assertEqual(
            urls(plan),
            ["https://www.news.com/", "https://news.com/", "https://www.news.com/about"],
        )
        self.assertTrue(all(entry.bucket is Bucket.NEW for entry in plan))

    def test_larger_cap_skips_duplicate_templates(self) -> None:
        plan = select(self.site_pages(), 10, NOW, SEED, per_site=10)
        self.assertEqual(
            urls(plan),
            [
                "https://www.news.com/",
                "https://news.com/",
                "https://www.news.com/about",
                "https://www.news.com/article/2",
                "https://www.news.com/sport",
                "https://www.news.com/weather",
            ],
        )

    def test_cap_holds_across_buckets(self) -> None:
        pages = [
            page(f"https://shop.com/item-{name}", verdict="js-error", severity=2)
            for name in ("alpha", "bravo", "charlie", "delta")
        ] + [page("https://shop.com/"), page("https://other.com/")]
        plan = select(pages, 20, NOW, SEED, per_site=2)
        counts = Counter(entry.site for entry in plan)
        self.assertEqual(counts["shop.com"], 2)
        self.assertEqual(counts["other.com"], 1)


class BudgetSplitTest(unittest.TestCase):
    def test_rechecks_capped_at_forty_percent_most_severe_first(self) -> None:
        verdicts = [
            ("crash", 5),
            ("hang", 4),
            ("blank", 4),
            ("content-missing", 3),
            ("js-error", 2),
            ("visual-diff", 1),
        ]
        failing = [
            page(f"https://fail{index}.com/", verdict=verdict, severity=severity)
            for index, (verdict, severity) in enumerate(verdicts)
        ]
        fresh = [page(f"https://new{index}.com/") for index in range(10)]
        plan = select(failing + fresh, 10, NOW, SEED)

        self.assertEqual(len(plan), 10)
        rechecks = [entry for entry in plan if entry.bucket is Bucket.RECHECK]
        self.assertEqual(len(rechecks), 4)
        self.assertEqual(urls(rechecks)[0], "https://fail0.com/")
        self.assertEqual(set(urls(rechecks)), {f"https://fail{index}.com/" for index in range(4)})
        # The failing pages beyond the recheck share take the rotation reserve.
        buckets = Counter(entry.bucket for entry in plan)
        self.assertEqual((buckets[Bucket.NEW], buckets[Bucket.ROTATION]), (4, 2))

    def test_small_budget_has_no_room_for_rechecks(self) -> None:
        pages = [page("https://fail.com/", verdict="crash", severity=5), page("https://new.com/")]
        plan = select(pages, 2, NOW, SEED)
        self.assertEqual([entry.bucket for entry in plan], [Bucket.NEW, Bucket.ROTATION])

    def test_buckets_come_in_order(self) -> None:
        pages = [
            page("https://rot.com/", checked_days_ago=3, verdict="ok", severity=0),
            page("https://new.com/"),
            page("https://fail.com/", verdict="content-missing", severity=3),
        ]
        plan = select(pages, 5, NOW, SEED)
        self.assertEqual(
            [(entry.url, entry.bucket) for entry in plan],
            [
                ("https://fail.com/", Bucket.RECHECK),
                ("https://new.com/", Bucket.NEW),
                ("https://rot.com/", Bucket.ROTATION),
            ],
        )

    def test_non_failures_are_not_rechecked(self) -> None:
        pages = [
            page(f"https://{verdict}.com/", verdict=verdict, severity=0)
            for verdict in ("ok", "reference-failed", "harness-error")
        ]
        plan = select(pages, 10, NOW, SEED)
        self.assertEqual({entry.bucket for entry in plan}, {Bucket.ROTATION})
        self.assertEqual(len(plan), 3)

    def test_flaky_pages_are_rechecked(self) -> None:
        plan = select([page("https://a.com/", verdict="flaky", severity=1)], 5, NOW, SEED)
        self.assertEqual(plan[0].bucket, Bucket.RECHECK)

    def test_failing_page_outside_candidates_is_rechecked(self) -> None:
        pages = [
            page("https://blog.com/post/1", visited_days_ago=90, verdict="crash", severity=5),
            page("https://blog.com/post/2", visited_days_ago=1),
        ]
        plan = select(pages, 5, NOW, SEED)
        self.assertEqual(
            [(entry.url, entry.bucket) for entry in plan],
            [("https://blog.com/post/1", Bucket.RECHECK), ("https://blog.com/post/2", Bucket.NEW)],
        )


class DrainTest(unittest.TestCase):
    """The plan must not spend every night on the next article of the same big sites."""

    def test_a_site_offers_its_root_and_most_recent_templates_only(self) -> None:
        tested = [
            page("https://big.com/", checked_days_ago=5, verdict="ok", severity=0),
            page("https://big.com/a/1", visited_days_ago=1, checked_days_ago=5, verdict="ok"),
            page("https://big.com/b/1", visited_days_ago=2, checked_days_ago=5, verdict="ok"),
        ]
        # Older pages of other sections, never tested.
        older = [page(f"https://big.com/{name}/1", visited_days_ago=30) for name in "cdefgh"]
        plan = select([*tested, *older], 10, NOW, SEED)

        self.assertEqual({entry.bucket for entry in plan}, {Bucket.ROTATION})
        self.assertEqual(sorted(urls(plan)), sorted(entry.url for entry in tested))

    def test_rotation_keeps_a_share_while_new_pages_wait(self) -> None:
        fresh = [page(f"https://new{index}.com/") for index in range(30)]
        tested = [
            page(f"https://old{index}.com/", checked_days_ago=20, verdict="ok", severity=0)
            for index in range(10)
        ]
        plan = select([*fresh, *tested], 10, NOW, SEED)

        buckets = Counter(entry.bucket for entry in plan)
        self.assertEqual(buckets[Bucket.ROTATION], 2)
        self.assertEqual(buckets[Bucket.NEW], 8)

    def test_new_pages_fill_what_rotation_does_not_need(self) -> None:
        fresh = [page(f"https://new{index}.com/") for index in range(30)]
        tested = [page("https://old.com/", checked_days_ago=20, verdict="ok", severity=0)]
        plan = select([*fresh, *tested], 10, NOW, SEED)
        self.assertEqual(Counter(entry.bucket for entry in plan)[Bucket.NEW], 9)


class UnjudgedTest(unittest.TestCase):
    def test_a_page_loaded_without_a_verdict_waits_before_it_is_new_again(self) -> None:
        recent = page("https://recent.com/", attempted_days_ago=1)
        old = page("https://old.com/", attempted_days_ago=8)
        plan = select([recent, old], 10, NOW, SEED)
        self.assertEqual([(entry.url, entry.bucket) for entry in plan], [(old.url, Bucket.NEW)])


class RecheckOrderTest(unittest.TestCase):
    def test_decided_failures_are_not_rechecked(self) -> None:
        decided = page("https://wontfix.com/", verdict="crash", severity=5, decided=True)
        open_issue = page("https://open.com/", verdict="js-error", severity=2)
        plan = select([decided, open_issue], 10, NOW, SEED)
        buckets = {entry.url: entry.bucket for entry in plan}
        self.assertEqual(buckets, {open_issue.url: Bucket.RECHECK, decided.url: Bucket.ROTATION})

    def test_rechecks_rotate_oldest_first_so_milder_failures_get_their_turn(self) -> None:
        crashes = [
            page(f"https://crash{index}.com/", verdict="crash", severity=5, checked_days_ago=1)
            for index in range(4)
        ]
        milder = page("https://js.com/", verdict="js-error", severity=2, checked_days_ago=6)
        plan = select([*crashes, milder], 5, NOW, SEED)  # Room for 2 rechecks.
        rechecks = [entry.url for entry in plan if entry.bucket is Bucket.RECHECK]
        self.assertEqual(rechecks[0], milder.url)
        self.assertEqual(len(rechecks), 2)


class OrderTest(unittest.TestCase):
    def test_new_pages_follow_site_weight(self) -> None:
        pages = [
            page("https://rare.com/", add_count=1, visited_days_ago=200),
            page("https://frequent.com/", add_count=50, visited_days_ago=200),
            # 3 visits this week outweigh 10 visits long ago.
            page("https://recent.com/", add_count=3, visited_days_ago=3),
            page("https://older.com/", add_count=10, visited_days_ago=60),
        ]
        plan = select(pages, 4, NOW, SEED)
        self.assertEqual(
            urls(plan),
            [
                "https://frequent.com/",
                "https://recent.com/",
                "https://older.com/",
                "https://rare.com/",
            ],
        )

    def test_weight_counts_all_visits_of_a_site(self) -> None:
        many = [page(f"https://many.com/{name}") for name in ("a", "b", "c", "d", "e", "f", "g")]
        plan = select([page("https://single.com/", add_count=3), *many], 1, NOW, SEED)
        self.assertEqual(plan[0].site, "many.com")

    def test_new_pages_round_robin_across_sites(self) -> None:
        pages = [
            page("https://heavy.com/", add_count=100),
            page("https://heavy.com/a"),
            page("https://heavy.com/b"),
            page("https://light.com/", add_count=1),
            page("https://light.com/a"),
            page("https://light.com/b"),
        ]
        plan = select(pages, 4, NOW, SEED)
        self.assertEqual(
            [entry.site for entry in plan], ["heavy.com", "light.com", "heavy.com", "light.com"]
        )

    def test_rotation_takes_longest_untested_first(self) -> None:
        pages = [
            page("https://a.com/", checked_days_ago=1, verdict="ok", severity=0),
            page("https://b.com/", checked_days_ago=30, verdict="ok", severity=0),
            page("https://c.com/", checked_days_ago=10, verdict="ok", severity=0),
            page("https://d.com/", checked_days_ago=20, verdict="reference-failed", severity=0),
        ]
        plan = select(pages, 3, NOW, SEED)
        self.assertEqual(urls(plan), ["https://b.com/", "https://d.com/", "https://c.com/"])

    def test_rotation_round_robins_across_sites(self) -> None:
        pages = [
            page("https://a.com/", checked_days_ago=50, verdict="ok", severity=0),
            page("https://a.com/x", checked_days_ago=49, verdict="ok", severity=0),
            page("https://b.com/", checked_days_ago=5, verdict="ok", severity=0),
        ]
        plan = select(pages, 2, NOW, SEED)
        self.assertEqual(urls(plan), ["https://a.com/", "https://b.com/"])


class DeterminismTest(unittest.TestCase):
    def pages(self) -> list[Page]:
        # Many equally weighted sites, so only the seed decides their order.
        pages = [page(f"https://site{index:02d}.com/") for index in range(30)]
        pages += [
            page(f"https://site{index:02d}.com/x", checked_days_ago=2, verdict="ok", severity=0)
            for index in range(30)
        ]
        pages += [
            page(f"https://bad{index}.com/", verdict="js-error", severity=2) for index in range(5)
        ]
        return pages

    def test_same_seed_same_plan(self) -> None:
        first = select(self.pages(), 12, NOW, SEED)
        self.assertEqual(select(self.pages(), 12, NOW, SEED), first)

    def test_input_order_does_not_matter(self) -> None:
        expected = select(self.pages(), 12, NOW, SEED)
        shuffled = self.pages()
        random.Random(7).shuffle(shuffled)
        self.assertEqual(select(shuffled, 12, NOW, SEED), expected)

    def test_seed_breaks_ties(self) -> None:
        plans = {tuple(urls(select(self.pages(), 12, NOW, f"run-{index}"))) for index in range(5)}
        self.assertGreater(len(plans), 1)

    def test_integer_seed(self) -> None:
        self.assertEqual(select(self.pages(), 5, NOW, 42), select(self.pages(), 5, NOW, 42))


class FromRowTest(unittest.TestCase):
    def row(self, **changes: object) -> dict[str, object]:
        row: dict[str, object] = {
            "url": "https://a.com/",
            "host": "a.com",
            "site": "a.com",
            "template": "https://a.com/",
            "source": "hister",
            "add_count": 2,
            "hister_updated": 1_760_000_000,
            "last_checked": "2026-10-23T08:00:00+00:00",
            "last_run_id": "20261023T080000Z-aaaa",
            "last_verdict": "ok",
            "last_severity": 0,
        }
        row.update(changes)
        return row

    def test_from_store_row(self) -> None:
        built = Page.from_row(self.row())
        self.assertEqual(built.last_checked, datetime(2026, 10, 23, 8, 0, tzinfo=UTC))
        self.assertEqual((built.add_count, built.last_verdict, built.last_severity), (2, "ok", 0))

    def test_timestamps_without_offset_are_utc(self) -> None:
        for text in ("2026-10-23T08:00:00", "2026-10-23T08:00:00Z"):
            with self.subTest(text=text):
                self.assertEqual(
                    Page.from_row(self.row(last_checked=text)).last_checked,
                    datetime(2026, 10, 23, 8, 0, tzinfo=UTC),
                )

    def test_decided_signatures_and_last_attempt(self) -> None:
        row = self.row(
            last_verdict="crash",
            last_signatures=["crash:a.rs:1", "js:x"],
            last_attempted="2026-10-23T03:00:00Z",
        )
        self.assertFalse(Page.from_row(row, frozenset({"crash:a.rs:1"})).decided)
        decided = Page.from_row(row, frozenset({"crash:a.rs:1", "js:x"}))
        self.assertTrue(decided.decided)
        self.assertEqual(decided.last_attempted, datetime(2026, 10, 23, 3, tzinfo=UTC))

    def test_never_checked_row(self) -> None:
        built = Page.from_row(
            self.row(last_checked=None, last_verdict=None, last_severity=None, template=None)
        )
        self.assertIsNone(built.last_checked)
        self.assertEqual(select([built], 1, NOW, SEED)[0].bucket, Bucket.NEW)


if __name__ == "__main__":
    unittest.main()
