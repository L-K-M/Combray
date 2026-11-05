#!/usr/bin/env python3
"""Vendor PDFium-wasm (@embedpdf/pdfium) into the native library, compressed.

    python3 -I scripts/vendor-pdfium.py [--tarball <pdfium-2.15.1.tgz>]

    registry.npmjs.org tarball ──sha512 (npm integrity)──▶ package/dist/pdfium.wasm
                                                          package/dist/index.browser.js
                                  ──SHA-256 pins──▶ gzip -9n ──▶ native/src/pdf/vendor/*.gz
                                                    package/LICENSE, LICENSE.pdfium ──▶ *.LICENSE

The package is read into memory; nothing from it reaches the disk except the four files above,
each checked against its SHA-256 pin. Compressing needs GNU gzip on PATH: `gzip -9n` stores no
name or time, so the same input gives the same output with the same gzip (Python's zlib would
give other bytes than the committed files). native/src/pdf.rs embeds the .gz files and its host
tests inflate them against the same SHA-256 pins. To update PDFium, change VERSION and all the
pins together (docs/BUILDING.md, "Updating PDFium").
"""

import argparse
import base64
import hashlib
import io
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import urllib.request


ROOT = Path(__file__).resolve().parent.parent
VENDOR = ROOT / "native/src/pdf/vendor"

PACKAGE = "@embedpdf/pdfium"
VERSION = "2.15.1"
TARBALL_URL = f"https://registry.npmjs.org/{PACKAGE}/-/pdfium-{VERSION}.tgz"
# The `dist.integrity` the registry publishes for this version.
INTEGRITY = "sha512-qDH4gEkGSQT0iM+07Hpm3LyvoB7PEFbW3Qyk6+LPkEYLJYfHagkHM1OzYlDq2EDOhJRguykTkzdMOT3/w27x5Q=="
# Tarball member -> (file written under VENDOR, SHA-256 of the uncompressed member).
COMPRESSED = {
    "package/dist/pdfium.wasm": ("pdfium.wasm.gz", "5e4cd023c3dad4a895b3571ca573d3fc51bac6de48360d95283134385b954eaa"),
    "package/dist/index.browser.js": ("index.browser.js.gz", "2b8b6db33451f2599385697fe5bf634abdfbb093fec497febd7fb60b5aeea8ca"),
}
# Written as they are; the notices quote them (native/bundled-notices.toml).
LICENSES = {
    "package/LICENSE": ("embedpdf-pdfium.LICENSE", "f5031b66adba8ef5ef57666deff980a7f2ccff5c8a8c22a8117e854d2b8dfcd3"),
    "package/LICENSE.pdfium": ("pdfium.LICENSE", "b033ffb8fc19c23ca81f7e98019ab658cc6f4cf14587c7c6a2a67fb0f6ac0f5a"),
}
MAX_TARBALL_BYTES = 64 * 1024 * 1024
DOWNLOAD_TIMEOUT_SECONDS = 120


def check_integrity(data: bytes, integrity: str) -> None:
    """Fails unless [data] matches an npm `sha512-<base64>` integrity string."""
    algorithm, _, expected = integrity.partition("-")
    if algorithm != "sha512":
        raise ValueError(f"unsupported integrity algorithm {algorithm!r}")
    actual = base64.b64encode(hashlib.sha512(data).digest()).decode()
    if actual != expected:
        raise ValueError(f"tarball integrity sha512-{actual} does not match the pinned {integrity}")


def check_sha256(name: str, data: bytes, expected: str) -> None:
    actual = hashlib.sha256(data).hexdigest()
    if actual != expected:
        raise ValueError(f"{name}: SHA-256 {actual} does not match the pinned {expected}")


def download() -> bytes:
    """The package tarball, read into memory."""
    with urllib.request.urlopen(TARBALL_URL, timeout=DOWNLOAD_TIMEOUT_SECONDS) as response:
        data = response.read(MAX_TARBALL_BYTES + 1)
    if len(data) > MAX_TARBALL_BYTES:
        raise ValueError(f"{TARBALL_URL} is larger than {MAX_TARBALL_BYTES} bytes")
    return data


def members(tarball: bytes, names: list[str]) -> dict[str, bytes]:
    """The named regular files of the tarball, read in memory."""
    found: dict[str, bytes] = {}
    with tarfile.open(fileobj=io.BytesIO(tarball), mode="r:gz") as archive:
        for member in archive.getmembers():
            if member.name not in names:
                continue
            if not member.isfile() or member.name in found:
                raise ValueError(f"{member.name} is not a single regular file in the tarball")
            found[member.name] = archive.extractfile(member).read()
    missing = [name for name in names if name not in found]
    if missing:
        raise ValueError(f"the tarball lacks {missing}")
    return found


def gzip_n9(data: bytes) -> bytes:
    """`gzip -9n`: best compression, no file name or time stamp in the header."""
    if shutil.which("gzip") is None:
        raise OSError("gzip is not on PATH; GNU gzip writes the committed files (see the module help)")
    return subprocess.run(["gzip", "-9n"], input=data, check=True, capture_output=True).stdout


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--tarball", type=Path, help=f"use this downloaded {PACKAGE} {VERSION} tarball")
    args = parser.parse_args()

    tarball = args.tarball.read_bytes() if args.tarball else download()
    check_integrity(tarball, INTEGRITY)

    files = members(tarball, list(COMPRESSED) + list(LICENSES))
    for name, (_, digest) in (COMPRESSED | LICENSES).items():
        check_sha256(name, files[name], digest)

    VENDOR.mkdir(parents=True, exist_ok=True)
    for name, (output, _) in COMPRESSED.items():
        compressed = gzip_n9(files[name])
        (VENDOR / output).write_bytes(compressed)
        print(f"{(VENDOR / output).relative_to(ROOT)}: {len(files[name])} -> {len(compressed)} bytes")
    for name, (output, _) in LICENSES.items():
        (VENDOR / output).write_bytes(files[name])
        print(f"{(VENDOR / output).relative_to(ROOT)}: from {name}")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, subprocess.CalledProcessError, tarfile.TarError) as error:
        sys.exit(f"vendor-pdfium: {error}")
