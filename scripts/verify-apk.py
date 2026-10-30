#!/usr/bin/env python3
"""Verify packaged native libraries and print the APK's architecture for artifact naming.

    python3 -I scripts/verify-apk.py <apk> [--expected-arch arm64|x86_64]
        [--variant debug|release --version <versionName>]
    python3 -I scripts/verify-apk.py --notices-only <apk>

Uses the standard library. Checks packaging, ELF headers and CRCs, not runtime support
or signing. --variant cross-checks the adjacent Gradle output-metadata.json for the
variant, application ID, output filename and version; it does not bind source content.
Every APK must also carry the third-party notices shown under Settings › About;
--notices-only checks just those, for APKs built without the engine.
"""

import argparse
import json
from pathlib import Path
import struct
import sys
import zipfile


# ELF e_machine values from the System V ABI and AArch64 ELF specification.
ABIS = {"arm64-v8a": ("arm64", 183), "x86_64": ("x86_64", 62)}
LIBRARIES = ("libcombray_engine.so", "libc++_shared.so")
ELF_HEADER_SIZE = 64
ELF_SHARED_OBJECT = 3
COPY_CHUNK_BYTES = 1024 * 1024

# Notice files (scripts/generate-notices.py, the Licensee Gradle plugin) and the components the
# engine's notices must name, each with a phrase its texts contain, so a truncated text fails.
NOTICES = "assets/notices/native.json"
RUST_NOTICES = "assets/notices/Rust-Licenses.html"
JVM_NOTICES = "assets/app/cash/licensee/artifacts.json"
REQUIRED_NOTICES = {
    "servo": "Mozilla Public License",
    "LLVM libc++": "LLVM Exceptions to the Apache 2.0 License",
    "Rust standard library": "The Rust Standard Library is dual-licensed",
    # The PDF viewer's renderer, embedded in the engine library (native/src/pdf.rs). PDFium's
    # text ends with the Apache License the package's LICENSE.pdfium appends to it.
    "PDFium": "Apache License",
    "@embedpdf/pdfium": "MIT License",
}


def verify_metadata(apk_path, variant, version):
    metadata = json.loads(apk_path.with_name("output-metadata.json").read_text())
    if not isinstance(metadata, dict) or not isinstance(metadata.get("artifactType"), dict):
        raise ValueError("Invalid Gradle output metadata")
    elements = metadata.get("elements", [])
    expected_id = "ch.lkmc.combray" + (".debug" if variant == "debug" else "")
    if (
        metadata.get("artifactType", {}).get("type") != "APK"
        or metadata.get("variantName") != variant
        or metadata.get("applicationId") != expected_id
        or not isinstance(elements, list)
        or len(elements) != 1
        or not isinstance(elements[0], dict)
        or elements[0].get("outputFile") != apk_path.name
        or elements[0].get("versionName") != version
    ):
        raise ValueError("APK does not match the current Gradle output metadata")


def read_json(apk, name):
    try:
        return json.loads(apk.read(name))
    except ValueError as error:
        raise ValueError(f"{name} is not valid JSON: {error}") from error


def verify_notices(apk):
    producers = {
        NOTICES: "scripts/generate-notices.py",
        RUST_NOTICES: "scripts/generate-notices.py",
        JVM_NOTICES: "the Licensee Gradle plugin",
    }
    for name, producer in producers.items():
        if name not in apk.namelist() or apk.getinfo(name).file_size == 0:
            raise ValueError(f"APK is missing {name} or it is empty; see {producer}")

    native = read_json(apk, NOTICES)
    try:
        texts = native["texts"]
        components = {component["name"]: component["texts"] for component in native["components"]}
        missing = [
            name for name, phrase in REQUIRED_NOTICES.items()
            if not any(phrase in texts[index] for index in components.get(name, ()))
        ]
    except (KeyError, TypeError, IndexError) as error:
        raise ValueError(f"{NOTICES} is malformed: {error!r}") from error
    if missing:
        raise ValueError(f"{NOTICES} lacks the license text of {', '.join(missing)}")

    if b"The Rust Standard Library" not in apk.read(RUST_NOTICES):
        raise ValueError(f"{RUST_NOTICES} is not the Rust standard library's notices")
    libraries = read_json(apk, JVM_NOTICES)
    if not isinstance(libraries, list) or not libraries:
        raise ValueError(f"{JVM_NOTICES} lists no libraries")


def verify_apk(apk_path, expected_arch=None, notices_only=False):
    with zipfile.ZipFile(apk_path) as apk:
        entries = apk.infolist()
        names = [entry.filename for entry in entries]
        if len(names) != len(set(names)):
            raise ValueError("APK contains duplicate ZIP entries")
        if notices_only:
            verify_notices(apk)
            return None

        native_names = [name for name in names if name.startswith("lib/") and name.endswith(".so")]
        abi_names = {name.split("/")[1] for name in native_names}
        if len(abi_names) != 1 or not abi_names.issubset(ABIS):
            raise ValueError(
                "APK needs libcombray_engine.so and libc++_shared.so for exactly one supported ABI; "
                "run scripts/build-native.sh first"
            )

        abi = next(iter(abi_names))
        architecture, machine = ABIS[abi]
        if expected_arch is not None and architecture != expected_arch:
            raise ValueError(f"APK contains {architecture}, expected {expected_arch}")
        for library in LIBRARIES:
            if f"lib/{abi}/{library}" not in names:
                raise ValueError(f"APK is missing lib/{abi}/{library}; run scripts/build-native.sh")

        for name in native_names:
            if len(name.split("/")) != 3:
                raise ValueError(f"Invalid native library path: {name}")
            with apk.open(name) as library:
                header = library.read(ELF_HEADER_SIZE)
                if len(header) != ELF_HEADER_SIZE or header[:7] != b"\x7fELF\x02\x01\x01":
                    raise ValueError(f"Invalid 64-bit little-endian ELF library: {name}")
                file_type, actual_machine, version = struct.unpack_from("<HHI", header, 16)
                if (file_type, actual_machine, version) != (ELF_SHARED_OBJECT, machine, 1):
                    raise ValueError(f"ELF library does not match {abi}: {name}")
                # Read to EOF so a valid header cannot hide a truncated or corrupt library.
                while library.read(COPY_CHUNK_BYTES):
                    pass

        verify_notices(apk)
    return architecture


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--expected-arch", choices=[value[0] for value in ABIS.values()])
    parser.add_argument("--variant", choices=["debug", "release"])
    parser.add_argument("--version")
    parser.add_argument("--notices-only", action="store_true", help="check only the third-party notices")
    args = parser.parse_args()
    if (args.variant is None) != (args.version is None):
        parser.error("--variant and --version must be supplied together")
    if args.notices_only and args.expected_arch is not None:
        parser.error("--notices-only checks no native libraries; drop --expected-arch")

    try:
        if args.variant is not None:
            verify_metadata(args.apk, args.variant, args.version)
        architecture = verify_apk(args.apk, args.expected_arch, args.notices_only)
        if architecture is not None:
            print(architecture)
    except (OSError, ValueError, zipfile.BadZipFile, RuntimeError) as error:
        print(f"!! APK verification failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
