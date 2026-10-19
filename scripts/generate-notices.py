#!/usr/bin/env python3
"""Generate the engine's third-party notices for the About screen.

    crates linked into the engine ──cargo about──┐
    native/bundled-notices.toml (C/C++ code) ────┼──▶ app/src/main/assets/notices/native.json
    LLVM section of the pinned NDK's notices ────┘

The output records hashes of its inputs; scripts/tests/test_notices.py fails CI when an input
changed without regenerating. Java/Kotlin libraries are listed by the Licensee Gradle plugin at
build time instead.

Run on the native build host after changing native/Cargo.lock, the Servo pin or the NDK:
    ANDROID_SDK_ROOT=... scripts/generate-notices.py
"""

import hashlib
import json
import os
import re
import subprocess
import sys
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
NATIVE = ROOT / "native"
OUTPUT = ROOT / "app/src/main/assets/notices/native.json"
ENV_SCRIPT = ROOT / "scripts/m0/env.sh"
REGISTRY = Path(os.environ.get("CARGO_HOME", Path.home() / ".cargo")) / "registry/src"

# Inputs whose change requires regenerating; the test recomputes these.
HASHED_INPUTS = ["native/Cargo.lock", "native/about.toml", "native/bundled-notices.toml", "scripts/generate-notices.py"]

# Text the JVM libraries (all Apache-2.0, checked by Licensee) are shown with.
JVM_LICENSES = ["Apache-2.0"]


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


def ndk_section(marker: str) -> str:
    """The block of the NDK's toolchain notices that starts at [marker], up to the next rule."""
    sdk = os.environ.get("ANDROID_SDK_ROOT") or sys.exit("set ANDROID_SDK_ROOT")
    notice = Path(sdk) / "ndk" / ndk_version() / "NOTICE.toolchain"
    lines = notice.read_text().splitlines()
    start = next((i for i, line in enumerate(lines) if line.strip() == marker), None)
    if start is None:
        sys.exit(f"'{marker}' not found in {notice}")
    end = next((i for i in range(start, len(lines)) if re.fullmatch(r"=+", lines[i].strip())), len(lines))
    return "\n".join(lines[start:end]).strip() + "\n"


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

    bundled = tomllib.loads((NATIVE / "bundled-notices.toml").read_text())["library"]
    for library in bundled:
        indices = []
        version = None
        if "crate" in library:
            version = locked_version(library["crate"])
            source = crate_source(library["crate"], version)
            indices += [texts.add((source / file).read_text(errors="replace")) for file in library.get("files", [])]
        if "spdx-text" in library:
            indices.append(spdx_text(spdx, library["spdx-text"]))
        if "text" in library:
            indices.append(texts.add(library["text"]))
        if "ndk-notice" in library:
            version = f"NDK {ndk_version()}"
            indices.append(texts.add(ndk_section(library["ndk-notice"])))
        if not indices:
            sys.exit(f"{library['name']} has no license text")
        components[(library["name"], version or "")] = {
            "name": library["name"], "version": version, "license": library["license"], "texts": indices, "bundled_in": library.get("crate"),
        }

    missing = [identifier for identifier in JVM_LICENSES if identifier not in spdx]
    if missing:
        sys.exit(f"no text for JVM licenses {missing}")

    notices = {
        "inputs": {name: sha256(ROOT / name) for name in HASHED_INPUTS} | {"ndk": ndk_version()},
        "spdx": {identifier: spdx[identifier] for identifier in sorted(spdx)},
        "components": sorted(components.values(), key=lambda entry: (entry["name"].lower(), entry["version"] or "")),
        "texts": texts.texts,
    }
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(json.dumps(notices, ensure_ascii=False, separators=(",", ":")) + "\n")
    print(f"{OUTPUT.relative_to(ROOT)}: {len(notices['components'])} components, {len(texts.texts)} texts")


if __name__ == "__main__":
    main()
