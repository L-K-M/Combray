#!/usr/bin/env python3
"""Generate the engine's third-party notices for the About screen.

    crates linked into the engine ──cargo about──┐
    native/bundled-notices.toml (C/C++, JS, ─────┤
      data, crate texts cargo-about lacks)       ├──▶ app/src/main/assets/notices/native.json
    LLVM section of the pinned NDK's notices ────┤
    pinned Rust toolchain's COPYRIGHT-library ───┴──▶ app/src/main/assets/notices/Rust-Licenses.html

The output records hashes of its inputs; scripts/tests/test_notices.py fails CI when an input
changed without regenerating. Java/Kotlin libraries are listed by the Licensee Gradle plugin at
build time instead.

Run on the native build host after changing native/Cargo.lock, the Servo pin, the Rust
toolchain or the NDK:
    ANDROID_SDK_ROOT=... scripts/generate-notices.py
"""

import hashlib
import json
import os
import re
import subprocess
import sys
import tomllib
from html.parser import HTMLParser
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
NATIVE = ROOT / "native"
OUTPUT = ROOT / "app/src/main/assets/notices/native.json"
# The standard library's notices, verbatim from the toolchain that links it into the engine.
RUST_OUTPUT = OUTPUT.parent / "Rust-Licenses.html"
ENV_SCRIPT = ROOT / "scripts/m0/env.sh"
RUST_TOOLCHAIN = NATIVE / "rust-toolchain.toml"
SERVO = ROOT / "servo-checkout/servo"
REGISTRY = Path(os.environ.get("CARGO_HOME", Path.home() / ".cargo")) / "registry/src"


def hashed_inputs(config: dict) -> list[str]:
    """Inputs whose change requires regenerating; the test recomputes these. The Servo pin counts:
    Servo's crates are path dependencies, so a new pin can change their texts without touching
    Cargo.lock. Every `repo-files` text bundled-notices.toml names counts too."""
    repo_files = {file for entries in config.values() for entry in entries for file in entry.get("repo-files", [])}
    return [
        "native/Cargo.lock", "native/about.toml", "native/bundled-notices.toml", "native/rust-toolchain.toml",
        "scripts/generate-notices.py", "scripts/m0/servo-pin.sh",
    ] + sorted(repo_files)


HASHED_INPUTS = hashed_inputs(tomllib.loads((NATIVE / "bundled-notices.toml").read_text()))

# Text the JVM libraries (all Apache-2.0, checked by Licensee) are shown with.
JVM_LICENSES = ["Apache-2.0"]

# An SPDX template's copyright line, which cargo-about prints for a crate without license files:
# "<owner>" (BSD), "<copyright holders>" (MIT), "<Owner Organization Name>" (NCSA).
TEMPLATE = re.compile(r"<(owner( organization name)?|copyright holders?)>", re.IGNORECASE)
RULE = re.compile(r"=+")


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def ndk_version() -> str:
    match = re.search(r'^export COMBRAY_NDK_VERSION="([^"]+)"', ENV_SCRIPT.read_text(), re.M)
    if not match:
        sys.exit(f"no COMBRAY_NDK_VERSION in {ENV_SCRIPT}")
    return match.group(1)


def locked_version(name: str) -> str:
    lock = tomllib.loads((NATIVE / "Cargo.lock").read_text())
    versions = [package["version"] for package in lock["package"] if package["name"] == name]
    if len(versions) != 1:
        sys.exit(f"{name} is locked at {len(versions)} versions; expected one")
    return versions[0]


def crate_source(name: str, version: str) -> Path:
    matches = sorted(REGISTRY.glob(f"*/{name}-{version}"))
    if not matches:
        sys.exit(f"{name} {version} is not in {REGISTRY}; run a native build first")
    return matches[0]


def notice_section(lines: list[str], first: str, last: str) -> str | None:
    """The notices from heading [first] through the section under heading [last], or None.

    Headings sit between two rules of '='; a section runs to the next rule after its heading's
    closing rule.
    """
    start = next((i for i, line in enumerate(lines) if line.strip() == first), None)
    if start is None:
        return None
    heading = next((i for i in range(start, len(lines)) if lines[i].strip() == last), None)
    if heading is None:
        return None
    body = heading + 1
    if body < len(lines) and RULE.fullmatch(lines[body].strip()):
        body += 1
    end = next((i for i in range(body, len(lines)) if RULE.fullmatch(lines[i].strip())), len(lines))
    return "\n".join(lines[start:end]).strip() + "\n"


def ndk_section(first: str, last: str) -> str:
    """A block of the pinned NDK's toolchain notices (see notice_section)."""
    sdk = os.environ.get("ANDROID_SDK_ROOT") or sys.exit("set ANDROID_SDK_ROOT")
    notice = Path(sdk) / "ndk" / ndk_version() / "NOTICE.toolchain"
    section = notice_section(notice.read_text().splitlines(), first, last)
    if section is None:
        sys.exit(f"'{first}' … '{last}' not found in {notice}")
    return section


def rust_channel() -> str:
    return tomllib.loads(RUST_TOOLCHAIN.read_text())["toolchain"]["channel"]


def rust_std_notices(channel: str) -> Path:
    """The pinned toolchain's notices for the standard library, which is linked into the engine."""
    result = subprocess.run(
        ["rustup", "run", channel, "rustc", "--print", "sysroot"], check=True, capture_output=True, text=True,
    )
    path = Path(result.stdout.strip()) / "share/doc/rust/COPYRIGHT-library.html"
    if not path.is_file():
        sys.exit(f"{path} is missing; reinstall the {channel} toolchain")
    return path


class HtmlText(HTMLParser):
    """Plain text of an HTML notice, for the notices dialog, which shows text only."""

    BLOCKS = {"br", "details", "div", "h1", "h2", "h3", "li", "p", "pre", "summary", "ul"}

    def __init__(self) -> None:
        super().__init__()
        self.parts: list[str] = []
        self.hidden = 0
        self.pre = 0

    def handle_starttag(self, tag: str, attrs: list) -> None:
        self.hidden += tag in ("head", "script", "style")
        self.pre += tag == "pre"
        if tag in self.BLOCKS:
            self.parts.append("\n")

    def handle_endtag(self, tag: str) -> None:
        # A stray end tag must not drive a counter negative and hide the rest of the text.
        self.hidden = max(0, self.hidden - (tag in ("head", "script", "style")))
        self.pre = max(0, self.pre - (tag == "pre"))
        if tag in self.BLOCKS:
            self.parts.append("\n")

    def handle_data(self, data: str) -> None:
        if self.hidden:
            return
        if not self.pre:
            data = re.sub(r"\s+", " ", data)
            if not self.parts or self.parts[-1].endswith("\n"):
                data = data.lstrip()
        self.parts.append(data)

    @classmethod
    def convert(cls, html: str) -> str:
        parser = cls()
        parser.feed(html)
        parser.close()
        lines = [line.rstrip() for line in "".join(parser.parts).splitlines()]
        return re.sub(r"\n{3,}", "\n\n", "\n".join(lines)).strip() + "\n"


def cargo_about() -> dict:
    result = subprocess.run(
        ["cargo", "about", "generate", "--format", "json", "--config", "about.toml", "--locked"],
        cwd=NATIVE, check=True, capture_output=True, text=True,
    )
    return json.loads(result.stdout)


def spdx_text(spdx: dict[str, int], identifier: str) -> int:
    """Index of the canonical text for [identifier]; 0 is a valid index."""
    if identifier not in spdx:
        sys.exit(f"no {identifier} text from cargo-about")
    return spdx[identifier]


class Texts:
    """License texts, each stored once; components refer to them by index."""

    def __init__(self) -> None:
        self.texts: list[str] = []
        self.index: dict[str, int] = {}

    def add(self, text: str) -> int:
        text = text.replace("\r\n", "\n").strip() + "\n"
        if text not in self.index:
            self.index[text] = len(self.texts)
            self.texts.append(text)
        return self.index[text]

    def add_files(self, entry: dict, crate: Path | None = None) -> list[int]:
        """The texts a bundled-notices.toml entry names: `files` from the crate's source,
        `repo-files` from this repository, `servo-files` from the pinned Servo checkout."""
        indices = [self.add((crate / file).read_text(errors="replace")) for file in entry.get("files", [])]
        indices += [self.add((ROOT / file).read_text()) for file in entry.get("repo-files", [])]
        indices += [self.add((SERVO / file).read_text()) for file in entry.get("servo-files", [])]
        return indices


def templated(components: list[dict], texts: list[str]) -> list[str]:
    """Components shown with an SPDX template instead of their copyright notice."""
    return [
        f"{component['name']} {component['version']}" for component in components
        if any(TEMPLATE.search(texts[index]) for index in component["texts"])
    ]


def main() -> None:
    about = cargo_about()
    texts = Texts()
    components: dict[tuple[str, str], dict] = {}

    # One entry per crate; a dual-licensed crate lists every license text cargo-about chose.
    for license in about["licenses"]:
        text = texts.add(license["text"])
        for use in license["used_by"]:
            crate = use["crate"]
            entry = components.setdefault(
                (crate["name"], crate["version"]),
                {"name": crate["name"], "version": crate["version"], "license": crate.get("license") or license["id"], "texts": []},
            )
            if text not in entry["texts"]:
                entry["texts"].append(text)

    # Canonical SPDX texts: the most common text cargo-about reported for each identifier.
    spdx: dict[str, int] = {}
    for identifier in {license["id"] for license in about["licenses"]}:
        candidates = [license for license in about["licenses"] if license["id"] == identifier]
        best = max(candidates, key=lambda license: len(license["used_by"]))
        spdx[identifier] = texts.add(best["text"])

    config = tomllib.loads((NATIVE / "bundled-notices.toml").read_text())

    # Crates that publish no license file with their copyright line: their real texts replace the
    # SPDX template cargo-about filled in. Without a version the entry follows the locked one.
    for crate in config.get("crate", []):
        matches = [
            entry for (name, version), entry in components.items()
            if name == crate["name"] and crate.get("version", version) == version
        ]
        if not matches:
            sys.exit(f"[[crate]] {crate['name']} {crate.get('version', '')} matches no crate cargo-about listed")
        for entry in matches:
            source = crate_source(entry["name"], entry["version"]) if "files" in crate else None
            replacements = texts.add_files(crate, source)
            if not replacements:
                sys.exit(f"[[crate]] {crate['name']} names no license text")
            kept = [index for index in entry["texts"] if not TEMPLATE.search(texts.texts[index])]
            entry["texts"] = kept + [index for index in replacements if index not in kept]

    for library in config["library"]:
        version = library.get("version")
        source = None
        if "crate" in library:
            version = locked_version(library["crate"])
            source = crate_source(library["crate"], version)
        indices = texts.add_files(library, source)
        if "spdx-text" in library:
            indices.append(spdx_text(spdx, library["spdx-text"]))
        if "text" in library:
            indices.append(texts.add(library["text"]))
        if "ndk-notice" in library:
            version = f"NDK {ndk_version()}"
            indices.append(texts.add(ndk_section(library["ndk-notice"], library["ndk-notice-last-heading"])))
        if not indices:
            sys.exit(f"{library['name']} has no license text")
        joined = "".join(texts.texts[index] for index in indices)
        missing = [phrase for phrase in library.get("requires-text", []) if phrase not in joined]
        if missing:
            sys.exit(f"{library['name']}: its license text lacks {missing}")
        components[(library["name"], version or "")] = {
            "name": library["name"], "version": version, "license": library["license"], "texts": indices, "bundled_in": library.get("crate"),
        }

    # The Rust standard library is linked into the engine like any crate; cargo-about never lists it.
    channel = rust_channel()
    rust_notices = rust_std_notices(channel)
    components[("Rust standard library", channel)] = {
        "name": "Rust standard library", "version": channel, "license": "MIT OR Apache-2.0",
        "texts": [texts.add(HtmlText.convert(rust_notices.read_text()))], "bundled_in": None,
    }

    missing = [identifier for identifier in JVM_LICENSES if identifier not in spdx]
    if missing:
        sys.exit(f"no text for JVM licenses {missing}")

    placeholders = templated(list(components.values()), texts.texts)
    if placeholders:
        sys.exit(f"shown with an SPDX template, not their copyright notice: {placeholders}; add [[crate]] entries")

    notices = {
        "inputs": {name: sha256(ROOT / name) for name in HASHED_INPUTS} | {"ndk": ndk_version()},
        "spdx": {identifier: spdx[identifier] for identifier in sorted(spdx)},
        "components": sorted(components.values(), key=lambda entry: (entry["name"].lower(), entry["version"] or "")),
        "texts": texts.texts,
    }
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(json.dumps(notices, ensure_ascii=False, separators=(",", ":")) + "\n")
    RUST_OUTPUT.write_bytes(rust_notices.read_bytes())
    print(f"{OUTPUT.relative_to(ROOT)}: {len(notices['components'])} components, {len(texts.texts)} texts")
    print(f"{RUST_OUTPUT.relative_to(ROOT)}: Rust {channel}")


if __name__ == "__main__":
    main()
