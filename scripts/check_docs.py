#!/usr/bin/env python3
"""Check the bootstrap's local documentation links and square icon, using stdlib."""

from __future__ import annotations

import re
import struct
import sys
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import unquote, urlsplit


ROOT = Path(__file__).resolve().parents[1]
REQUIRED = (
    "README.md", "AGENTS.md", "LICENSE", "THIRD_PARTY_NOTICES.md",
    "docs/IMPLEMENTATION_PLAN.md", "docs/INTEGRATIONS.md",
    "docs/design/README.md", "docs/design/combray-concepts.html",
    "docs/design/combray-concepts.fragment.html",
    "media-sources/README.md", "media-sources/icon.png", "media-sources/icon-prompt.txt",
)
LINK = re.compile(r"!?\[[^\]\n]*\]\(([^)\n]+)\)")
FENCE = re.compile(r"^\s*(`{3,}|~{3,})")


def prose(source: str) -> str:
    """Omit fenced examples; documentation links in prose are the check's scope."""
    output = []
    fence = None
    for line in source.splitlines():
        match = FENCE.match(line)
        if match:
            marker = match.group(1)
            if fence is None:
                fence = marker
            elif marker[0] == fence[0] and len(marker) >= len(fence):
                fence = None
            continue
        if fence is None:
            output.append(line)
    return "\n".join(output)


def anchors(path: Path) -> set[str]:
    # GitHub-style heading slugs for the simple headings used in this repository.
    found = set()
    counts: dict[str, int] = {}
    for line in prose(path.read_text(encoding="utf-8")).splitlines():
        if not re.match(r"^#{1,6}\s", line):
            continue
        heading = re.sub(r"^#{1,6}\s+", "", line).strip().rstrip("#").strip()
        slug = re.sub(r"[^\w\- ]", "", heading.lower()).replace(" ", "-")
        count = counts.get(slug, 0)
        counts[slug] = count + 1
        found.add(f"{slug}-{count}" if count else slug)
    return found


class Images(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.sources: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag == "img":
            source = dict(attrs).get("src")
            if source:
                self.sources.append(source)


class Preview(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.frames: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag == "iframe":
            source = dict(attrs).get("data-srcdoc")
            if source:
                self.frames.append(source)


def main() -> int:
    errors = []
    for relative in REQUIRED:
        if not (ROOT / relative).is_file():
            errors.append(f"missing required file: {relative}")

    documents = sorted(
        path for path in ROOT.rglob("*.md")
        if not any(part.startswith(".") or part in {"build", "target", "node_modules", "servo-checkout"}
                   for part in path.relative_to(ROOT).parts)
    )
    links_checked = 0
    for path in documents:
        source = prose(path.read_text(encoding="utf-8"))
        parser = Images()
        parser.feed(source)
        targets = [match.group(1).strip().strip("<>") for match in LINK.finditer(source)]
        for target in targets + parser.sources:
            parts = urlsplit(target)
            if parts.scheme or parts.netloc:
                continue  # External links are documented evidence, not network CI checks.
            links_checked += 1
            resolved = (path.parent / unquote(parts.path)).resolve() if parts.path else path
            if not resolved.is_relative_to(ROOT):
                errors.append(f"{path.relative_to(ROOT)}: nonportable local link {target}")
            elif not resolved.exists():
                errors.append(f"{path.relative_to(ROOT)}: missing link target {target}")
            elif parts.fragment and resolved.suffix == ".md" and unquote(parts.fragment) not in anchors(resolved):
                errors.append(f"{path.relative_to(ROOT)}: missing heading {target}")

    icon = ROOT / "media-sources/icon.png"
    if icon.is_file():
        raw = icon.read_bytes()
        if len(raw) < 33 or raw[:8] != b"\x89PNG\r\n\x1a\n" or raw[12:16] != b"IHDR":
            errors.append("icon.png: invalid PNG header")
        else:
            width, height, _depth, color = struct.unpack(">IIBB", raw[16:26])
            if width != height or width < 512:
                errors.append(f"icon.png: expected square source >=512px, got {width}x{height}")
            # Original is RGB. Check chunks, not arbitrary compressed byte patterns.
            offset, transparent = 8, color in {4, 6}
            while offset + 12 <= len(raw):
                size = int.from_bytes(raw[offset:offset + 4], "big")
                kind = raw[offset + 4:offset + 8]
                transparent |= kind == b"tRNS"
                offset += size + 12
                if kind == b"IEND":
                    break
            if transparent or color not in {0, 2}:
                errors.append("icon.png: expected the original opaque RGB/grayscale source")

    fragment = ROOT / "docs/design/combray-concepts.fragment.html"
    exported = ROOT / "docs/design/combray-concepts.html"
    if fragment.is_file() and exported.is_file():
        preview = Preview()
        preview.feed(exported.read_text(encoding="utf-8"))
        source = fragment.read_text(encoding="utf-8").strip()
        if len(preview.frames) != 1 or source not in preview.frames[0]:
            errors.append("concept export: expected the current editable fragment in the standalone preview")

    if errors:
        print("Repository checks failed:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print(f"OK: {len(documents)} Markdown files, {links_checked} local links, required assets and square opaque PNG.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
