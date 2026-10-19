#!/usr/bin/env python3
"""Verify packaged native libraries and print the APK's architecture for artifact naming.

    python3 -I scripts/verify-apk.py <apk> [--expected-arch arm64|x86_64]
        [--variant debug|release --version <versionName>]

Uses the standard library. Checks packaging, ELF headers and CRCs, not runtime support
or signing. --variant also checks the adjacent Gradle output-metadata.json so an old
signed APK cannot be staged after a new unsigned build.
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


def verify_apk(apk_path, expected_arch=None):
    with zipfile.ZipFile(apk_path) as apk:
        entries = apk.infolist()
        names = [entry.filename for entry in entries]
        if len(names) != len(set(names)):
            raise ValueError("APK contains duplicate ZIP entries")

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

    return architecture


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--expected-arch", choices=[value[0] for value in ABIS.values()])
    parser.add_argument("--variant", choices=["debug", "release"])
    parser.add_argument("--version")
    args = parser.parse_args()
    if (args.variant is None) != (args.version is None):
        parser.error("--variant and --version must be supplied together")

    try:
        if args.variant is not None:
            verify_metadata(args.apk, args.variant, args.version)
        print(verify_apk(args.apk, args.expected_arch))
    except (OSError, ValueError, zipfile.BadZipFile, RuntimeError) as error:
        print(f"!! APK verification failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
