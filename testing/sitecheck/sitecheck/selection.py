"""Choose which pages one run tests, within a page budget.

A run cannot test the whole history, so the plan mixes three buckets, in the order
the runner should test them:

1. re-checks of pages whose latest verdict was a failure not yet decided (wontfix or
   upstream), to notice fixes and regressions: at most RECHECK_SHARE of the budget,
   longest unchecked first and most severe first within a day;
2. pages never tested, one site at a time in order of how much the owner uses it,
   leaving ROTATION_SHARE of the budget to the third bucket;
3. pages tested before, round-robin across sites, longest untested first; then
   never-tested pages again if budget is left.

No site gets more than `per_site` pages in a plan. A site's candidates are its most
visited root page ("https://host/"), then the most recently visited page of each of
its `per_site` - 1 most recent URL templates, so fifty articles of one news site cost
one test and a site with thousands of pages cannot keep the "new" bucket full. A page
loaded without a verdict about Servo (no baseline, harness failure) counts as never
tested again after UNJUDGED_RETRY_AFTER.

Pure: the caller passes the store's view of eligible pages and their latest outcome,
and the run id as seed, so a plan can be reproduced.
"""

from __future__ import annotations

import math
import random
from collections import Counter
from collections.abc import Iterable, Iterator, Mapping, Sequence
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from enum import StrEnum
from typing import Any
from urllib.parse import urlsplit

from sitecheck.model import VerdictKind

DEFAULT_PER_SITE = 3

# Known failures may use at most this share of the budget, so they cannot crowd out
# pages that were never tested.
RECHECK_SHARE = 0.4
# Pages tested before get at least this share, so a page that passed is tested again
# (a regression after a fix or an upstream merge) even while new pages wait.
ROTATION_SHARE = 0.2
# A page whose loads gave no verdict about Servo is tried again after this long.
UNJUDGED_RETRY_AFTER = timedelta(days=3)

# A site visited this recently is what the owner uses now. The bonus is added to the
# site's log2 visit weight, so it counts as much as four times the visits.
RECENT_VISIT_WINDOW = timedelta(days=14)
RECENT_VISIT_BONUS = 2.0

_ROOT_PATHS = ("", "/")
# Sorts before any real timestamp.
_NEVER = datetime.min.replace(tzinfo=UTC)


class Bucket(StrEnum):
    """Why a page is in the plan; shown in the report."""

    RECHECK = "recheck"  # Failed last time.
    NEW = "new"  # Never tested.
    ROTATION = "rotation"  # Tested before; due again.


@dataclass(frozen=True)
class Page:
    """An eligible page with its latest outcome; the last_* fields are None if never tested."""

    url: str
    site: str
    template: str | None  # None: the page is its own template.
    add_count: int | None  # Visits Hister counted; None for seeds.
    hister_updated: int | None  # Unix seconds of the latest visit; None for seeds.
    last_checked: datetime | None  # Timezone-aware.
    last_verdict: str | None  # A model.VerdictKind value.
    last_severity: int | None
    # The latest load, with or without a verdict about Servo. Timezone-aware.
    last_attempted: datetime | None = None
    # The latest verdict's signatures are all issues marked wontfix or upstream.
    decided: bool = False

    @classmethod
    def from_row(
        cls, row: Mapping[str, Any], decided_signatures: frozenset[str] = frozenset()
    ) -> Page:
        """Build from a store selection row, whose times are ISO-8601 text in UTC.

        `decided_signatures` are the issues marked wontfix or upstream.
        """
        signatures = row.get("last_signatures") or []
        return cls(
            url=row["url"],
            site=row["site"],
            template=row.get("template"),
            add_count=row.get("add_count"),
            hister_updated=row.get("hister_updated"),
            last_checked=_optional_utc(row.get("last_checked")),
            last_verdict=row.get("last_verdict"),
            last_severity=row.get("last_severity"),
            last_attempted=_optional_utc(row.get("last_attempted")),
            decided=bool(signatures) and all(sig in decided_signatures for sig in signatures),
        )


@dataclass(frozen=True)
class PlannedPage:
    url: str
    site: str
    bucket: Bucket


def select(
    pages: Iterable[Page],
    budget: int,
    now: datetime,
    rng_seed: int | str,
    per_site: int = DEFAULT_PER_SITE,
) -> list[PlannedPage]:
    """Plan up to `budget` pages for a run, in the order they should be tested.

    `pages` must all be eligible. The same pages, `now` and seed always give the same
    plan, whatever order `pages` come in; the seed only breaks ties between sites.
    """
    if budget < 0:
        raise ValueError("budget must not be negative")
    if per_site < 1:
        raise ValueError("per_site must be at least 1")
    if now.tzinfo is None:
        raise ValueError("now must be timezone-aware")

    ordered = _validated(pages)
    if budget == 0 or not ordered:
        return []

    by_site: dict[str, list[Page]] = {}
    for page in ordered:
        by_site.setdefault(page.site, []).append(page)
    rng = random.Random(rng_seed)
    sites = {
        name: _Site(
            weight=_site_weight(site_pages, now),
            tiebreak=rng.random(),
            candidates=_candidates(site_pages, per_site),
        )
        for name, site_pages in sorted(by_site.items())
    }

    plan = _Plan(budget, per_site)
    _add_rechecks(plan, ordered, sites, limit=math.floor(budget * RECHECK_SHARE))
    rotation_reserve = math.floor(budget * ROTATION_SHARE)
    _add_never_tested(plan, sites, now, limit=budget - rotation_reserve)
    _add_due(plan, sites)
    _add_never_tested(plan, sites, now, limit=budget)
    return plan.entries


@dataclass(frozen=True)
class _Site:
    weight: float
    tiebreak: float  # Seeded random number that orders sites of equal weight.
    candidates: tuple[Page, ...]  # See `_candidates`.


class _Plan:
    """The plan being built: enforces the budget, the per-site cap and uniqueness."""

    def __init__(self, budget: int, per_site: int) -> None:
        self.entries: list[PlannedPage] = []
        self._budget = budget
        self._per_site = per_site
        self._urls: set[str] = set()
        self._site_counts: Counter[str] = Counter()
        self._bucket_counts: Counter[Bucket] = Counter()

    def count(self, bucket: Bucket) -> int:
        return self._bucket_counts[bucket]

    def __len__(self) -> int:
        return len(self.entries)

    def add(self, page: Page, bucket: Bucket) -> None:
        if len(self.entries) >= self._budget:
            return
        if page.url in self._urls or self._site_counts[page.site] >= self._per_site:
            return
        self.entries.append(PlannedPage(url=page.url, site=page.site, bucket=bucket))
        self._urls.add(page.url)
        self._site_counts[page.site] += 1
        self._bucket_counts[bucket] += 1


def _add_rechecks(
    plan: _Plan, pages: Sequence[Page], sites: Mapping[str, _Site], limit: int
) -> None:
    # Every failing page qualifies, not only the site's current candidates: the page
    # that showed a problem is the one that shows whether it is fixed. The longest
    # unchecked go first, so every open failure gets its turn; within a day of last
    # checks, the most severe.
    failing = sorted(
        (page for page in pages if _failed(page)),
        key=lambda page: (
            (page.last_checked or _NEVER).date(),
            -(page.last_severity or 0),
            -sites[page.site].weight,
            page.last_checked or _NEVER,
            sites[page.site].tiebreak,
            page.url,
        ),
    )
    for page in failing:
        if plan.count(Bucket.RECHECK) >= limit:
            return
        plan.add(page, Bucket.RECHECK)


def _add_never_tested(
    plan: _Plan, sites: Mapping[str, _Site], now: datetime, *, limit: int
) -> None:
    by_weight = sorted(sites.values(), key=lambda site: (-site.weight, site.tiebreak))
    queues = [[page for page in site.candidates if _never_tested(page, now)] for site in by_weight]
    for page in _round_robin(queues):
        if len(plan) >= limit:
            return
        plan.add(page, Bucket.NEW)


def _never_tested(page: Page, now: datetime) -> bool:
    if page.last_checked is not None:
        return False
    return page.last_attempted is None or now - page.last_attempted >= UNJUDGED_RETRY_AFTER


def _add_due(plan: _Plan, sites: Mapping[str, _Site]) -> None:
    """Round-robin again: every site's longest-untested page, oldest first, then the next."""
    due: list[tuple[tuple[int, datetime, float, str], Page]] = []
    for site in sites.values():
        tested = sorted(
            (page for page in site.candidates if page.last_checked is not None),
            key=lambda page: (page.last_checked or _NEVER, page.url),
        )
        due.extend(
            ((round_number, page.last_checked or _NEVER, site.tiebreak, page.url), page)
            for round_number, page in enumerate(tested)
        )
    for _, page in sorted(due, key=lambda entry: entry[0]):
        plan.add(page, Bucket.ROTATION)


def _validated(pages: Iterable[Page]) -> list[Page]:
    """The pages sorted by URL, so input order cannot change the plan."""
    by_url: dict[str, Page] = {}
    for page in pages:
        if page.url in by_url:
            # No URL in the message: it would be browsing history.
            raise ValueError("the same page appears twice in the selection input")
        if page.last_checked is not None and page.last_checked.tzinfo is None:
            raise ValueError("last_checked must be timezone-aware")
        if page.last_attempted is not None and page.last_attempted.tzinfo is None:
            raise ValueError("last_attempted must be timezone-aware")
        if page.last_verdict is not None:
            VerdictKind(page.last_verdict)  # Raises ValueError for an unknown verdict.
        by_url[page.url] = page
    return [by_url[url] for url in sorted(by_url)]


def _failed(page: Page) -> bool:
    if page.last_verdict is None or page.decided:
        return False
    return VerdictKind(page.last_verdict).is_failure


def _site_weight(pages: Sequence[Page], now: datetime) -> float:
    visits = sum(max(page.add_count or 1, 1) for page in pages)
    weight = math.log2(1 + visits)
    recent_since = (now - RECENT_VISIT_WINDOW).timestamp()
    if any(
        page.hister_updated is not None and page.hister_updated >= recent_since for page in pages
    ):
        weight += RECENT_VISIT_BONUS
    return weight


def _candidates(pages: Sequence[Page], per_site: int) -> tuple[Page, ...]:
    """A site's pages worth testing, in order: its main root page, then the most recent
    page of each of the most recent templates, `per_site` pages in all."""
    roots = sorted(
        (page for page in pages if _is_root(page.url)),
        key=lambda page: (-(page.add_count or 0), -(page.hister_updated or 0), page.url),
    )
    by_recency = sorted(
        pages,
        key=lambda page: (page.hister_updated is None, -(page.hister_updated or 0), page.url),
    )

    chosen: list[Page] = []
    templates: set[str] = set()
    for page in roots[:1] + by_recency:
        if len(chosen) >= per_site:
            break
        template = page.template or page.url
        if template in templates:
            continue
        templates.add(template)
        chosen.append(page)
    return tuple(chosen)


def _is_root(url: str) -> bool:
    parts = urlsplit(url)
    return parts.path in _ROOT_PATHS and not parts.query


def _round_robin(queues: Sequence[Sequence[Page]]) -> Iterator[Page]:
    """The first page of every queue in order, then every second page, and so on."""
    depth = max((len(queue) for queue in queues), default=0)
    for index in range(depth):
        for queue in queues:
            if index < len(queue):
                yield queue[index]


def _optional_utc(value: object) -> datetime | None:
    if isinstance(value, str):
        return _parse_utc(value)
    return value if isinstance(value, datetime) else None


def _parse_utc(text: str) -> datetime:
    parsed = datetime.fromisoformat(text)
    if parsed.tzinfo is None:
        # The store writes UTC; an offset-less value is still UTC.
        return parsed.replace(tzinfo=UTC)
    return parsed
