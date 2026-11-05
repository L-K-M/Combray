"""The vendored PDFium-wasm files match the pins in scripts/vendor-pdfium.py; no network needed."""

import base64
import gzip
import hashlib
import importlib.util
import io
from pathlib import Path
import tarfile
import unittest


ROOT = Path(__file__).resolve().parents[2]


def load_vendor():
    spec = importlib.util.spec_from_file_location("vendor_pdfium", ROOT / "scripts/vendor-pdfium.py")
    vendor = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(vendor)
    return vendor


def tarball(files: dict[str, bytes]) -> bytes:
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode="w:gz") as archive:
        for name, data in files.items():
            info = tarfile.TarInfo(name)
            info.size = len(data)
            archive.addfile(info, io.BytesIO(data))
    return buffer.getvalue()


class VendorPdfiumTest(unittest.TestCase):
    def setUp(self):
        self.vendor = load_vendor()

    def test_the_committed_files_inflate_to_the_pinned_package_files(self):
        for member, (output, digest) in self.vendor.COMPRESSED.items():
            data = gzip.decompress((self.vendor.VENDOR / output).read_bytes())
            self.assertEqual(hashlib.sha256(data).hexdigest(), digest, member)

    def test_the_committed_files_carry_no_name_or_time(self):
        for output, _ in self.vendor.COMPRESSED.values():
            header = (self.vendor.VENDOR / output).read_bytes()[:10]
            flags, mtime = header[3], int.from_bytes(header[4:8], "little")
            self.assertEqual((flags, mtime), (0, 0), output)

    def test_the_committed_licenses_are_the_pinned_package_files(self):
        for member, (output, digest) in self.vendor.LICENSES.items():
            data = (self.vendor.VENDOR / output).read_bytes()
            self.assertEqual(hashlib.sha256(data).hexdigest(), digest, member)

    def test_gzip_is_required_on_path(self):
        from unittest import mock

        with mock.patch.object(self.vendor.shutil, "which", return_value=None):
            with self.assertRaisesRegex(OSError, "gzip is not on PATH"):
                self.vendor.gzip_n9(b"data")

    def test_the_package_licenses_are_vendored(self):
        self.assertIn("MIT License", (self.vendor.VENDOR / "embedpdf-pdfium.LICENSE").read_text())
        pdfium = (self.vendor.VENDOR / "pdfium.LICENSE").read_text()
        self.assertIn("Copyright 2014 PDFium Authors", pdfium)
        self.assertIn("Apache License", pdfium)

    def test_integrity_must_match(self):
        data = b"package"
        good = "sha512-" + base64.b64encode(hashlib.sha512(data).digest()).decode()
        self.vendor.check_integrity(data, good)
        with self.assertRaises(ValueError):
            self.vendor.check_integrity(data + b"!", good)
        with self.assertRaises(ValueError):
            self.vendor.check_integrity(data, "sha1-" + good.removeprefix("sha512-"))

    def test_a_changed_file_fails_its_pin(self):
        with self.assertRaises(ValueError):
            self.vendor.check_sha256("pdfium.wasm", b"other", hashlib.sha256(b"wasm").hexdigest())

    def test_only_named_regular_files_are_read(self):
        files = {"package/a": b"a", "package/b": b"b", "../escape": b"x"}
        self.assertEqual(self.vendor.members(tarball(files), ["package/a"]), {"package/a": b"a"})
        with self.assertRaises(ValueError):
            self.vendor.members(tarball(files), ["package/missing"])

    def test_a_duplicate_member_is_refused(self):
        buffer = io.BytesIO()
        with tarfile.open(fileobj=buffer, mode="w:gz") as archive:
            for data in (b"first", b"second"):
                info = tarfile.TarInfo("package/a")
                info.size = len(data)
                archive.addfile(info, io.BytesIO(data))
        with self.assertRaises(ValueError):
            self.vendor.members(buffer.getvalue(), ["package/a"])


if __name__ == "__main__":
    unittest.main()
