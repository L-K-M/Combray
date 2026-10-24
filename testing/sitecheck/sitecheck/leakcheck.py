"""Finds hosts, sites and URLs from the page inventory in text that is about to be shared.

The nightly task writes commits, branches and PR descriptions about Servo bugs it
found on the owner's sites. None of them may name those sites. This check scans
what is about to be published (each commit's added lines, file names and message in
a git range, since a push publishes every commit and not only the net change; the
branch name; and text files such as a PR body) for:

- any host or site in the store (eligible or not: an excluded page is still history),
  matched case-insensitively between word boundaries, so "example.co.uk" matches
  "www.example.co.uk", the file name "example.co.uk.html" and the branch name
  "fix-example.co.uk-menu", but not "myexample.co.uk" or "example.co.ukraine";
- any stored URL with a path, matched as host plus path, which also catches pages on
  allowlisted hosts (a Wikipedia article is a visit even though wikipedia.org is
  generic). A URL also matches without its query and shortened to its leading path
  segments, down to two ("github.com/owner/repo" for any page of that repository).
  Internationalized host names match in both spellings (Unicode and punycode).

Generic hosts that legitimately appear in code are allowlisted, and so are public
references the engine work cites (specifications, engine bug trackers, the project's
own repositories), host names and URLs alike. In git mode, a term on an added line
is ignored when the same file already contained it at the range's base, and a file
name when the file already existed there: touching such a line reveals nothing new.
Commit messages, branch names and text files have no such exception: naming a site
there ties it to a fix. Commits reachable from an excluded ref (an upstream release
merged in) are public already and skipped; a merge commit's own changes (a conflict
resolution) are scanned. IP literals that are not public and single-label hosts are
not matched (they are everywhere in code and say little about the owner). Binary
files are scanned by name only.

It also finds text that would reach the upstream Servo project, which must never be
contacted: cross-repository references to its issues and pull requests (GitHub links
the referenced issue back to a commit message or PR that names it) in commit messages
and text files, and @-mentions (which notify people) in text files, outside code. File
contents are not checked for these: GitHub neither links nor notifies from them.

The findings name the matched term: this output stays on the local machine.
"""

from __future__ import annotations

import dataclasses
import functools
import ipaddress
import re
import subprocess
import tempfile
from collections.abc import Iterable, Iterator, Mapping, Sequence
from dataclasses import dataclass
from enum import StrEnum
from pathlib import Path
from urllib.parse import urlsplit

# Hosts and sites that appear in code, tests and documentation for reasons unrelated to
# anyone's history. A subdomain of an entry is allowed too. Their URLs still count:
# a Wikipedia article or a GitHub repository is a visit.
ALLOWED_HOSTS = frozenset(
    {
        "example.com",
        "example.org",
        "example.net",
        "github.com",
        "wikipedia.org",
        "localhost",
        "web-platform.test",
    }
)

# Public references the engine work cites in commits and PRs: specifications, engine
# bug trackers and documentation, and the repositories the nightly task works in.
# Neither their names nor their URLs are terms, since commit messages are scanned
# without exception. An entry is a host (its subdomains included) or a host and path
# prefix (its pages included).
PUBLIC_REFERENCES = frozenset(
    {
        "w3.org",
        "whatwg.org",
        "csswg.org",
        "tc39.es",
        "ecma-international.org",
        "ietf.org",
        "rfc-editor.org",
        "unicode.org",
        "khronos.org",
        "mozilla.org",
        "servo.org",
        "webkit.org",
        "chromium.org",
        "crbug.com",
        "wpt.fyi",
        "wpt.live",
        "rust-lang.org",
        "docs.rs",
        "crates.io",
        "github.com/servo",
        "github.com/l-k-m/combray",
        "github.com/l-k-m/servo",
        "github.com/whatwg",
        "github.com/w3c",
        "github.com/web-platform-tests",
        "github.com/tc39",
        "github.com/rust-lang",
    }
)

# A maximal run of letters and digits in any script. A term starts at the start of
# one, so nothing alphanumeric precedes it.
_WORD = re.compile(r"[^\W_]+")
# What may not follow a host or site (it would make a longer name) ...
_NAME_CONTINUATION = re.compile(r"[^\W_]")
# ... or a URL fragment (it would continue the last path segment).
_URL_CONTINUATION = re.compile(r"[\w\-]")
_GIT_RANGE = re.compile(r"(?P<base>[^\s.][^\s]*?)\.\.(?P<head>[^\s.][^\s]*)")
_HUNK_HEADER = re.compile(
    r"^@@ -\d+(?:,(?P<old_count>\d+))? \+(?P<start>\d+)(?:,(?P<new_count>\d+))? @@"
)
_FILE_HEADER = "diff --git "
# A merge's combined diff ("git log --cc"): one prefix column per parent, and a
# hunk header with one "@" more than there are parents.
_COMBINED_FILE_HEADER = "diff --cc "
_COMBINED_HUNK_HEADER = re.compile(
    r"^(?P<ats>@@@+) (?P<parents>(?:-\d+(?:,\d+)? )+)\+(?P<start>\d+)(?:,(?P<count>\d+))? (?P=ats)"
)
_COMBINED_RANGE = re.compile(r"-\d+(?:,(?P<count>\d+))?")
_NEW_FILE_HEADER = "new file mode"
_NO_NEWLINE_MARKER = "\\"
# A URL also matches shortened to this many leading path segments, or more.
_URL_PREFIX_MIN_SEGMENTS = 2
_IDNA_PREFIX = "xn--"
# Separators in `git log` output, written by git from the format's %xNN escapes (an
# argument cannot hold them). NUL cannot occur in a patch's text lines.
_COMMIT_MARKER, _COMMIT_MARKER_FORMAT = "\0", "%x00"
_MESSAGE_MARKER, _MESSAGE_MARKER_FORMAT = "\x01", "%x01"
_HASH_END, _HASH_END_FORMAT = "\x02", "%x02"
_SHORT_HASH = 12

# An issue, pull request or discussion of a repository in the upstream organization,
# as a link or as an owner/repo#number reference. Either makes GitHub show the
# publishing commit or PR on the upstream issue's timeline.
_UPSTREAM_REFERENCE = re.compile(
    r"github\.com/servo/[\w.-]+/(?:issues|pull|discussions)/\d+|(?<![\w/.-])servo/[\w.-]+#\d+",
    re.IGNORECASE,
)
# A GitHub @-mention: not part of an e-mail address, a path or another word.
_MENTION = re.compile(r"(?<![\w@./+-])@([A-Za-z0-9][A-Za-z0-9-]{0,38})(?![\w-])")
# CSS at-rules, which test-case descriptions mention and GitHub does not resolve to
# accounts that exist for them; anything else that looks like a mention counts.
_AT_RULES = frozenset(
    {
        "charset", "container", "counter-style", "document", "font-face",
        "font-feature-values", "font-palette-values", "import", "keyframes", "layer",
        "media", "namespace", "page", "position-try", "property", "scope",
        "starting-style", "supports", "view-transition",
    }
)  # fmt: skip
_FENCE = re.compile(r"^\s*(`{3,}|~{3,})")
_CODE_SPAN = re.compile(r"(`+)(?:(?!\1).)+?\1")
_GIT_TIMEOUT_S = 120


class LeakcheckError(Exception):
    """The scan could not run (bad range, not a repository, git failed)."""


class TermKind(StrEnum):
    HOST = "host"
    SITE = "site"
    URL = "url"
    UPSTREAM_REFERENCE = "upstream reference"
    MENTION = "mention"


@dataclass(frozen=True)
class Leak:
    location: str  # A file path, "commit <sha>", "branch" or a text file.
    line: int | None  # 1-based; None for a file name or branch name.
    term: str
    kind: TermKind
    commit: str | None = None  # The commit that added a file line or file name.

    def __str__(self) -> str:
        where = self.location if self.line is None else f"{self.location}:{self.line}"
        if self.commit is not None:
            where += f" (commit {self.commit})"
        return f"{where}: matched {self.kind.value} {self.term}"


@dataclass(frozen=True)
class Inventory:
    """What to look for, lowercased. Build it with `inventory`."""

    names: Mapping[str, TermKind]  # Hosts and sites.
    urls_by_host: Mapping[str, tuple[str, ...]]  # host -> "host/path?query" fragments.

    @property
    def empty(self) -> bool:
        return not self.names and not self.urls_by_host

    @functools.cached_property
    def _by_first_word(self) -> Mapping[str, tuple[tuple[str, TermKind], ...]]:
        """Terms keyed by their first word, so a line is scanned once, not once per term."""
        index: dict[str, list[tuple[str, TermKind]]] = {}
        terms = [*self.names.items()]
        terms += [(url, TermKind.URL) for urls in self.urls_by_host.values() for url in urls]
        for term, kind in terms:
            first = _WORD.match(term)
            if first is not None:
                index.setdefault(first.group(0), []).append((term, kind))
        return {word: tuple(entries) for word, entries in index.items()}


def inventory(
    hosts: Iterable[str],
    sites: Iterable[str],
    urls: Iterable[str],
    allowed: Iterable[str] = ALLOWED_HOSTS,
) -> Inventory:
    """The terms to search for, from the store's hosts, sites and URLs."""
    allow = frozenset(name.lower().strip(".") for name in allowed) | _REFERENCE_HOSTS
    names: dict[str, TermKind] = {}
    for kind, values in ((TermKind.SITE, sites), (TermKind.HOST, hosts)):
        for value in values:
            for name in _spellings(value.lower().strip(".")):
                if _matchable(name) and not _is_allowed(name, allow):
                    names[name] = kind

    fragments: dict[str, set[str]] = {}
    for url in urls:
        for fragment in _url_fragments(url):
            host = fragment.split("/", 1)[0].split("?", 1)[0]
            if _matchable(host) and not _is_public_reference(fragment):
                fragments.setdefault(host, set()).add(fragment)
    return Inventory(
        names=names, urls_by_host={host: tuple(sorted(f)) for host, f in fragments.items()}
    )


def scan_text(location: str, text: str, terms: Inventory) -> list[Leak]:
    """Every term in `text`, by line."""
    return list(_scan_lines(location, enumerate(text.splitlines(), start=1), terms))


def scan_files(paths: Iterable[Path], terms: Inventory) -> list[Leak]:
    """Every term and upstream contact in the given text files (a PR body, a comment)."""
    leaks = []
    for path in paths:
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except OSError as error:
            raise LeakcheckError(f"cannot read {path}: {error.strerror}") from error
        leaks += scan_text(str(path), text, terms)
        leaks += scan_upstream_contact(str(path), text, mentions=True)
    return leaks


def scan_upstream_contact(location: str, text: str, *, mentions: bool) -> list[Leak]:
    """References to upstream issues in `text`, and with `mentions` its @-mentions.

    Mentions count only where GitHub notifies (PR and issue text, not commit messages),
    and not inside Markdown code, where GitHub ignores them.
    """
    leaks = []
    fence = None
    for number, line in enumerate(text.splitlines(), start=1):
        for match in _UPSTREAM_REFERENCE.finditer(line):
            leaks.append(
                Leak(location, number, match.group(0).lower(), TermKind.UPSTREAM_REFERENCE)
            )
        if not mentions:
            continue
        marker = _FENCE.match(line)
        if marker is not None:
            if fence is None:
                fence = marker.group(1)
            elif marker.group(1)[0] == fence[0] and len(marker.group(1)) >= len(fence):
                fence = None
            continue
        if fence is not None:
            continue
        for match in _MENTION.finditer(_CODE_SPAN.sub(" ", line)):
            if match.group(1).lower() not in _AT_RULES:
                leaks.append(Leak(location, number, match.group(0), TermKind.MENTION))
    return leaks


def scan_git_range(
    repo: Path, git_range: str, terms: Inventory, *, exclude_refs: Sequence[str] = ()
) -> list[Leak]:
    """Every term that the commits of `git_range` would publish.

    Each commit is scanned on its own, since a push publishes every commit and not
    only the net change: the lines and file names it adds (binary files by name), and
    its message. A merge commit's own lines (new relative to every parent, such as a
    conflict resolution) count as added. Commits reachable from `exclude_refs` (an
    upstream release that was merged in) are skipped: they are already public and
    not ours. Also scans the range's head when it names a branch (or HEAD on a
    branch), since branch names are published with a push. A term on an added line is
    dropped when the same file contained it at the base, and a file name when the
    file existed there; messages and the branch name are never exempt.
    """
    base, head = parse_range(repo, git_range)
    revisions = [f"{base}..{head}", *(f"^{_verified_commit(repo, ref)}" for ref in exclude_refs)]
    file_leaks: list[Leak] = []
    for commit, path, number, text in [
        *_commit_patches(repo, revisions),
        *_merge_patches(repo, revisions),
    ]:
        for leak in _scan_lines(path, [(number, text)], terms):
            file_leaks.append(dataclasses.replace(leak, commit=commit))
    leaks = _drop_preexisting_in_files(file_leaks, repo, base)
    for commit, message in _commit_messages(repo, revisions):
        leaks += scan_text(f"commit {commit}", message, terms)
        leaks += scan_upstream_contact(f"commit {commit}", message, mentions=False)
    branch = _branch_name(repo, head)
    if branch:
        leaks += _scan_lines("branch", [(None, branch)], terms)
    return leaks


def _drop_preexisting_in_files(leaks: list[Leak], repo: Path, base: str) -> list[Leak]:
    """`leaks` from file lines and names, without those the same file had at `base`.

    A line leak is dropped when its file contained the term at the base, a file-name
    leak when the file existed there. Anywhere else in the tree does not count: a
    large repository mentions tens of thousands of hosts somewhere.
    """
    existed: dict[str, bool] = {}
    had_term: dict[tuple[str, str], bool] = {}
    kept = []
    for leak in leaks:
        path = leak.location
        if leak.line is None:
            if path not in existed:
                existed[path] = _exists_at(repo, base, path)
            if existed[path]:
                continue
        else:
            key = (path, leak.term)
            if key not in had_term:
                had_term[key] = _present_at(repo, base, leak.term, path)
            if had_term[key]:
                continue
        kept.append(leak)
    return kept


def _scan_lines(
    location: str, lines: Iterable[tuple[int | None, str]], terms: Inventory
) -> Iterator[Leak]:
    index = terms._by_first_word
    for number, line in lines:
        lowered = line.lower()
        seen: set[tuple[str, TermKind]] = set()
        for word in _WORD.finditer(lowered):
            for term, kind in index.get(word.group(0), ()):
                if (term, kind) in seen or not _occurs_at(lowered, term, kind, word.start()):
                    continue
                seen.add((term, kind))
                yield Leak(location, number, term, kind)


def _occurs_at(text: str, term: str, kind: TermKind, position: int) -> bool:
    if not text.startswith(term, position):
        return False
    continuation = _URL_CONTINUATION if kind is TermKind.URL else _NAME_CONTINUATION
    return continuation.match(text, position + len(term)) is None


def _url_fragments(url: str) -> list[str]:
    """How a stored web URL with a path or query may be written: "host/path?query".

    A root URL with a query is also matched without its slash ("host?query"), as
    people write it. The path alone, and its leading segments down to
    _URL_PREFIX_MIN_SEGMENTS ("host/owner/repo" of "host/owner/repo/pull/42?x=1"),
    match too; a one-segment path is not shortened, since "host/item" or
    "host/watch" would match every page of that kind. Each comes with the host in
    both IDN spellings. A site root alone, or another scheme, gives nothing.
    """
    try:
        parts = urlsplit(url.strip())
        host = (parts.hostname or "").strip(".")
    except ValueError:
        return []
    if parts.scheme not in ("http", "https") or not host:
        return []
    path = parts.path if parts.path not in ("", "/") else ""
    if not path and not parts.query:
        return []
    query = f"?{parts.query}" if parts.query else ""
    tails = [f"{path or '/'}{query}"]
    if not path:
        tails.append(query)
    segments = [segment for segment in path.split("/") if segment]
    for count in range(_URL_PREFIX_MIN_SEGMENTS, len(segments) + 1):
        tails.append("/" + "/".join(segments[:count]))
    fragments: list[str] = []
    for spelling in _spellings(host.lower()):
        for tail in tails:
            fragment = f"{spelling}{tail}".lower()
            if fragment not in fragments:
                fragments.append(fragment)
    return fragments


def _spellings(name: str) -> list[str]:
    """`name` and, for an internationalized name, its other spelling (punycode or Unicode).

    Browsers report IDN hosts in punycode, while people write them in Unicode.
    """
    spellings = [name]
    try:
        if not name.isascii():
            spellings.append(name.encode("idna").decode("ascii").lower())
        elif _IDNA_PREFIX in name:
            spellings.append(name.encode("ascii").decode("idna").lower())
    except UnicodeError:
        pass  # Not a valid IDN: only the spelling as stored can occur.
    return spellings


def _matchable(name: str) -> bool:
    if "." not in name:
        return False
    try:
        address = ipaddress.ip_address(name)
    except ValueError:
        return True
    return address.is_global


def _is_allowed(name: str, allowed: frozenset[str]) -> bool:
    labels = name.split(".")
    return any(".".join(labels[index:]) in allowed for index in range(len(labels)))


_REFERENCE_HOSTS = frozenset(entry for entry in PUBLIC_REFERENCES if "/" not in entry)
_REFERENCE_PREFIXES = tuple(sorted(entry for entry in PUBLIC_REFERENCES if "/" in entry))


def _is_public_reference(fragment: str) -> bool:
    """Whether a "host/path" URL fragment is a page of a public reference."""
    host = fragment.split("/", 1)[0].split("?", 1)[0]
    if _is_allowed(host, _REFERENCE_HOSTS):
        return True
    return any(
        fragment == prefix or fragment.startswith((prefix + "/", prefix + "?"))
        for prefix in _REFERENCE_PREFIXES
    )


def parse_range(repo: Path, git_range: str) -> tuple[str, str]:
    """The base and head of a ``BASE..HEAD`` range, both verified to be commits."""
    match = _GIT_RANGE.fullmatch(git_range)
    if match is None or "..." in git_range:
        raise LeakcheckError(f"expected a range like BASE..HEAD, got {git_range!r}")
    base, head = match.group("base"), match.group("head")
    for ref in (base, head):
        _verified_commit(repo, ref)
    return base, head


def _verified_commit(repo: Path, ref: str) -> str:
    """The commit `ref` names; refuses anything git could read as an option."""
    if ref.startswith("-") or not ref.strip():
        raise LeakcheckError(f"not a revision: {ref!r}")
    try:
        return _git(repo, "rev-parse", "--verify", "--quiet", f"{ref}^{{commit}}").strip()
    except LeakcheckError:
        raise LeakcheckError(f"{ref!r} is not a commit in {repo}") from None


def _commit_patches(
    repo: Path, revisions: Sequence[str]
) -> Iterator[tuple[str, str, int | None, str]]:
    """(commit, path, line number, text) for every file name and added line in the range.

    File names come with line None. One `git log -p` for the whole range, read as a
    stream (a range can be large), so file names never pass through git's pathspec
    parser.
    """
    lines = _git_lines(
        repo,
        "-c",
        "core.quotePath=false",
        "log",
        "--reverse",
        "--no-merges",
        "--no-renames",
        "--patch",
        "--unified=0",
        "--no-color",
        "--no-ext-diff",
        f"--format={_COMMIT_MARKER_FORMAT}%H",
        *revisions,
        "--",
    )
    commit = ""
    path = ""
    number = 0
    old_left = new_left = 0  # Lines still to come in the current hunk.
    for line in lines:
        if old_left or new_left:
            if line.startswith("+"):
                yield commit, path, number, line[1:]
                number += 1
                new_left -= 1
            elif line.startswith("-"):
                old_left -= 1
            elif not line.startswith("\\"):  # "\ No newline at end of file" stays inside.
                old_left = new_left = 0
            continue
        if line.startswith(_COMMIT_MARKER):
            commit = line[len(_COMMIT_MARKER) :][:_SHORT_HASH]
        elif line.startswith(_FILE_HEADER):
            path = _header_path(line[len(_FILE_HEADER) :])
            yield commit, path, None, path
        elif header := _HUNK_HEADER.match(line):
            number = int(header.group("start"))
            old_left = int(header.group("old_count") or 1)
            new_left = int(header.group("new_count") or 1)


def _merge_patches(
    repo: Path, revisions: Sequence[str]
) -> Iterator[tuple[str, str, int | None, str]]:
    """(commit, path, line number, text) for the lines merge commits add themselves.

    Reads the combined diffs ("git log --cc"), which list only the files where the
    merge differs from every parent. A line counts when it is new relative to every
    parent ("++" with two parents): that is what a conflict resolution or an edit in
    the merge adds. Lines taken from one side are that side's commits, scanned there
    or excluded with them. A file new relative to every parent gives its name too.
    """
    lines = _git_lines(
        repo,
        "-c",
        "core.quotePath=false",
        "log",
        "--reverse",
        "--merges",
        "--no-renames",
        "--cc",
        "--unified=0",
        "--no-color",
        "--no-ext-diff",
        f"--format={_COMMIT_MARKER_FORMAT}%H",
        *revisions,
        "--",
    )
    commit = ""
    path = ""
    in_header = False
    width = 0  # Prefix columns of the current hunk: the number of parents.
    parents_left: list[int] = []  # Lines of each parent still to come in the hunk.
    result_left = 0
    number = 0
    for line in lines:
        if result_left or any(parents_left):
            prefix = line[:width]
            if line.startswith(_NO_NEWLINE_MARKER):
                continue
            if len(prefix) == width and set(prefix) <= {" ", "+", "-"}:
                if "-" in prefix:
                    # Removed: in the parents marked "-", not in the result.
                    for index, mark in enumerate(prefix):
                        if mark == "-":
                            parents_left[index] -= 1
                    continue
                # In the result, and in the parents marked " ".
                for index, mark in enumerate(prefix):
                    if mark == " ":
                        parents_left[index] -= 1
                if prefix == "+" * width:
                    yield commit, path, number, line[width:]
                number += 1
                result_left -= 1
                continue
            result_left, parents_left = 0, []  # Malformed: leave the hunk.
        if line.startswith(_COMMIT_MARKER):
            commit = line[len(_COMMIT_MARKER) :][:_SHORT_HASH]
        elif line.startswith(_COMBINED_FILE_HEADER):
            path = line[len(_COMBINED_FILE_HEADER) :]
            in_header = True
        elif in_header and line.startswith(_NEW_FILE_HEADER):
            yield commit, path, None, path
        elif header := _COMBINED_HUNK_HEADER.match(line):
            in_header = False
            width = len(header.group("ats")) - 1
            parents_left = [
                int(match.group("count") or 1)
                for match in _COMBINED_RANGE.finditer(header.group("parents"))
            ]
            number = int(header.group("start"))
            result_left = int(header.group("count") or 1)


def _header_path(rest: str) -> str:
    """The path in a "diff --git a/P b/P" header (renames are off, so both are P).

    A path git had to quote is returned as quoted, which still contains its text.
    """
    if rest.startswith("a/") and len(rest) % 2 == 1:
        length = (len(rest) - len("a/ b/")) // 2
        path = rest[2 : 2 + length]
        if rest[2 + length :] == f" b/{path}":
            return path
    return rest


def _commit_messages(repo: Path, revisions: Sequence[str]) -> Iterator[tuple[str, str]]:
    output = _git(
        repo,
        "log",
        "--reverse",
        f"--format={_MESSAGE_MARKER_FORMAT}%H{_HASH_END_FORMAT}%B",
        *revisions,
        "--",
    )
    for entry in output.split(_MESSAGE_MARKER)[1:]:
        commit, _, message = entry.partition(_HASH_END)
        yield commit[:_SHORT_HASH], message


def _branch_name(repo: Path, head: str) -> str | None:
    if head == "HEAD":
        try:
            return _git(repo, "symbolic-ref", "--quiet", "--short", "HEAD").strip() or None
        except LeakcheckError:
            return None  # A detached HEAD has no branch name to publish.
    try:
        full = _git(repo, "rev-parse", "--symbolic-full-name", head).strip()
    except LeakcheckError:
        return None
    return head if full.startswith("refs/heads/") else None


def _exists_at(repo: Path, base: str, path: str) -> bool:
    """Whether the file `path` existed at `base`."""
    result = subprocess.run(
        ["git", "-C", str(repo), "cat-file", "-e", f"{base}:{path}"],
        capture_output=True,
        timeout=_GIT_TIMEOUT_S,
        check=False,
    )
    return result.returncode == 0


def _present_at(repo: Path, base: str, term: str, path: str) -> bool:
    """Whether `term` occurs in the file `path` at `base` as the same whole domain or URL."""
    result = subprocess.run(
        # ":(literal)" keeps git from reading the path as a pattern.
        [
            "git",
            "-C",
            str(repo),
            "grep",
            "-I",
            "-i",
            "-h",
            "-F",
            "-e",
            term,
            base,
            "--",
            f":(literal){path}",
        ],
        capture_output=True,
        text=True,
        errors="replace",
        timeout=_GIT_TIMEOUT_S,
        check=False,
    )
    if result.returncode == 1:
        return False
    if result.returncode != 0:
        raise LeakcheckError(f"git grep failed: {result.stderr.strip()}")
    only = (
        Inventory(names={}, urls_by_host={term.split("/", 1)[0]: (term,)})
        if "/" in term
        else Inventory(names={term: TermKind.HOST}, urls_by_host={})
    )
    return bool(scan_text("base", result.stdout, only))


def _git_lines(repo: Path, *arguments: str) -> Iterator[str]:
    """git's output line by line (without line ends), for output too large to hold.

    stderr goes to a file rather than a pipe: a full stderr pipe would stall git
    while this reads stdout.
    """
    with tempfile.TemporaryFile() as error_file:
        try:
            process = subprocess.Popen(
                ["git", "-C", str(repo), *arguments],
                stdout=subprocess.PIPE,
                stderr=error_file,
                text=True,
                errors="replace",
            )
        except OSError as error:
            raise LeakcheckError(f"git {arguments[0]} failed: {error}") from error
        assert process.stdout is not None
        try:
            for line in process.stdout:
                yield line.rstrip("\n")
            if process.wait(timeout=_GIT_TIMEOUT_S) != 0:
                error_file.seek(0)
                message = error_file.read().decode("utf-8", "replace").strip()
                raise LeakcheckError(f"git {arguments[0]} failed: {message}")
        finally:
            if process.poll() is None:
                process.kill()
                process.wait()
            process.stdout.close()


def _git(repo: Path, *arguments: str) -> str:
    try:
        result = subprocess.run(
            ["git", "-C", str(repo), *arguments],
            capture_output=True,
            text=True,
            errors="replace",
            timeout=_GIT_TIMEOUT_S,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        raise LeakcheckError(f"git {arguments[0]} failed: {error}") from error
    if result.returncode != 0:
        raise LeakcheckError(f"git {arguments[0]} failed: {result.stderr.strip()}")
    return result.stdout
