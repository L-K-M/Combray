"""Tests for site_reports.validation: the report table, PNG structure, JSON decoding."""

from __future__ import annotations

import base64
import copy
import hashlib
import json
import unittest
import uuid
from collections.abc import Callable
from typing import Any

from site_reports import validation
from site_reports.validation import ErrorCode, Status, ValidationError
from tests import support

GOLDEN_ID = "6f1c2b9e-1d2a-4a5b-9c3d-2e1f0a9b8c7d"
PNG = support.png(3, 2)


def mutated(path: str, value: Any) -> Callable[[dict[str, Any]], None]:
    def apply(request: dict[str, Any]) -> None:
        *parents, key = path.split(".")
        target = request
        for parent in parents:
            target = target[parent]
        target[key] = value

    return apply


def removed(path: str) -> Callable[[dict[str, Any]], None]:
    def apply(request: dict[str, Any]) -> None:
        *parents, key = path.split(".")
        target = request
        for parent in parents:
            target = target[parent]
        del target[key]

    return apply


def png_with_url_unsafe_base64() -> bytes:
    """A PNG whose standard base64 has '+' or '/', so the URL-safe form differs."""
    for shade in range(256):
        data = support.png(3, 2, lambda x, y, shade=shade: (shade, 50 * x, 70 * y, 255))
        if set(base64.b64encode(data).decode("ascii")) & {"+", "/"}:
            return data
    raise AssertionError("no candidate PNG")


class ValidationTestCase(unittest.TestCase):
    def assert_rejected(
        self,
        body: bytes,
        code: ErrorCode,
        field: str | None,
        status: int = 400,
    ) -> ValidationError:
        with self.assertRaises(ValidationError) as caught:
            validation.parse_report(body, max_screenshot_bytes=support.MAX_SCREENSHOT_BYTES)
        error = caught.exception
        self.assertEqual((error.code, error.field, error.status), (code, field, status), error)
        return error

    def assert_request_rejected(
        self,
        request: dict[str, Any],
        code: ErrorCode,
        field: str | None,
        status: int = 400,
    ) -> ValidationError:
        return self.assert_rejected(support.encode(request), code, field, status)

    def assert_accepted(self, request: dict[str, Any]) -> validation.ParsedReport:
        return support.parse(request)


class GoldenRequestTest(ValidationTestCase):
    def test_the_golden_phone_request_parses(self) -> None:
        parsed = validation.parse_report(
            support.golden_bytes(), max_screenshot_bytes=support.MAX_SCREENSHOT_BYTES
        )

        golden = support.golden()
        self.assertEqual(parsed.id, GOLDEN_ID)
        self.assertEqual(parsed.screenshot, base64.b64decode(golden["screenshot"]["data"]))
        self.assertEqual(parsed.screenshot_sha256, golden["screenshot"]["sha256"])
        expected = copy.deepcopy(golden)
        del expected["screenshot"]["data"]
        self.assertEqual(parsed.metadata, expected)

    def test_the_golden_screenshot_is_a_real_image_of_its_declared_size(self) -> None:
        screenshot = support.golden()["screenshot"]
        data = base64.b64decode(screenshot["data"], validate=True)

        self.assertEqual(len(data), screenshot["bytes"])
        self.assertEqual(hashlib.sha256(data).hexdigest(), screenshot["sha256"])
        rows = support.rgba_rows(data)
        self.assertEqual(len(rows), screenshot["height"])
        self.assertEqual({len(row) for row in rows}, {4 * screenshot["width"]})

    def test_the_golden_file_has_the_wire_shape(self) -> None:
        golden = support.golden()

        self.assertEqual(
            list(golden),
            ["id", "created_at", "page", "comment", "client", "screenshot", "screenshot_omitted"],
        )
        self.assertEqual(
            list(golden["page"]),
            ["url", "url_edited", "title", "display", "load_failed", "crashed"],
        )
        self.assertEqual(
            list(golden["screenshot"]),
            ["media_type", "width", "height", "bytes", "sha256", "scaled", "data"],
        )
        self.assertNotIn("\n", golden["screenshot"]["data"])

    def test_the_content_digest_covers_the_canonical_metadata(self) -> None:
        parsed = validation.parse_report(
            support.golden_bytes(), max_screenshot_bytes=support.MAX_SCREENSHOT_BYTES
        )

        self.assertEqual(
            parsed.content_sha256,
            hashlib.sha256(parsed.metadata_json.encode("utf-8")).hexdigest(),
        )
        self.assertEqual(validation.content_digest(parsed.metadata), parsed.content_sha256)
        self.assertNotIn('"data"', parsed.metadata_json)
        self.assertEqual(
            parsed.metadata_json,
            json.dumps(parsed.metadata, sort_keys=True, separators=(",", ":"), ensure_ascii=False),
        )

    def test_formatting_and_key_order_do_not_change_the_digest(self) -> None:
        golden = support.golden()
        reordered = dict(reversed(list(golden.items())))
        reordered["client"] = dict(reversed(list(golden["client"].items())))

        digests = {
            support.parse(golden).content_sha256,
            support.parse(reordered).content_sha256,
            validation.parse_report(
                support.golden_bytes(), max_screenshot_bytes=support.MAX_SCREENSHOT_BYTES
            ).content_sha256,
        }

        self.assertEqual(len(digests), 1)

    def test_any_content_change_changes_the_digest(self) -> None:
        golden = support.golden()
        variants = [
            support.request(id=GOLDEN_ID, comment="Another comment."),
            support.request(id=GOLDEN_ID, client={**golden["client"], "android_sdk": 36}),
            support.with_screenshot(golden, support.png(2, 3), 2, 3),
            support.without_screenshot(golden),
        ]

        digests = {support.parse(golden).content_sha256}
        digests.update(support.parse(variant).content_sha256 for variant in variants)

        self.assertEqual(len(digests), len(variants) + 1)

    def test_non_ascii_text_is_digested_as_utf8(self) -> None:
        request = support.request(comment="Café, naïve, 東京")

        parsed = support.parse(request)

        self.assertIn("Café, naïve, 東京", parsed.metadata_json)
        self.assertEqual(
            parsed.content_sha256, hashlib.sha256(parsed.metadata_json.encode("utf-8")).hexdigest()
        )

    def test_every_omission_reason_parses_without_a_screenshot(self) -> None:
        for reason in (
            "user",
            "reader_view",
            "not_shown",
            "page_changed",
            "copy_failed",
            "too_large",
        ):
            with self.subTest(reason=reason):
                parsed = support.parse(support.without_screenshot(support.golden(), reason))

                self.assertIsNone(parsed.screenshot)
                self.assertIsNone(parsed.screenshot_sha256)
                self.assertEqual(parsed.metadata["screenshot_omitted"], reason)

    def test_repr_leaves_out_report_contents(self) -> None:
        parsed = support.parse(support.request(comment="distinctive-comment-7d1f"))
        change = validation.parse_status_change(b'{"status":"done","note":"distinctive-note-5e2a"}')

        self.assertNotIn("distinctive-comment-7d1f", repr(parsed))
        self.assertNotIn("example.org", repr(parsed))
        self.assertNotIn("distinctive-note-5e2a", repr(change))

    def test_report_ids_are_lowercase_uuid4(self) -> None:
        self.assertTrue(validation.is_report_id(GOLDEN_ID))
        self.assertTrue(validation.is_report_id(str(uuid.uuid4())))
        for value in (GOLDEN_ID.upper(), str(uuid.uuid1()), GOLDEN_ID + "\n", None, 7):
            with self.subTest(value=value):
                self.assertFalse(validation.is_report_id(value))


class ReportTableTest(ValidationTestCase):
    """One case per row of the validation table, plus its boundaries."""

    def check_rejections(
        self, cases: list[tuple[Callable[[dict[str, Any]], None], ErrorCode, str | None]]
    ) -> None:
        for index, (change, code, field) in enumerate(cases):
            with self.subTest(case=index, code=code, field=field):
                request = support.golden()
                change(request)
                self.assert_request_rejected(request, code, field)

    def test_top_level_and_object_keys_are_exact(self) -> None:
        self.check_rejections(
            [
                (mutated("extra", 1), ErrorCode.UNKNOWN_FIELD, "extra"),
                (mutated("not a plain key", 1), ErrorCode.UNKNOWN_FIELD, None),
                (mutated("page.extra", 1), ErrorCode.UNKNOWN_FIELD, "page.extra"),
                (mutated("screenshot.extra", 1), ErrorCode.UNKNOWN_FIELD, "screenshot.extra"),
                (removed("comment"), ErrorCode.MISSING_FIELD, "comment"),
                (removed("screenshot_omitted"), ErrorCode.MISSING_FIELD, "screenshot_omitted"),
                (removed("page.title"), ErrorCode.MISSING_FIELD, "page.title"),
                (removed("screenshot.scaled"), ErrorCode.MISSING_FIELD, "screenshot.scaled"),
            ]
        )

    def test_id_is_a_lowercase_uuid4(self) -> None:
        self.check_rejections(
            [
                (mutated("id", value), ErrorCode.INVALID_ID, "id")
                for value in (
                    GOLDEN_ID.upper(),
                    "6f1c2b9e-1d2a-1a5b-9c3d-2e1f0a9b8c7d",  # Version 1.
                    "6f1c2b9e-1d2a-4a5b-7c3d-2e1f0a9b8c7d",  # Wrong variant.
                    GOLDEN_ID + "\n",
                    GOLDEN_ID.replace("-", ""),
                    "",
                    7,
                    None,
                )
            ]
        )

    def test_created_at_is_rfc3339_utc(self) -> None:
        self.assert_accepted(support.request(created_at="2026-11-04T09:12:44Z"))
        self.assert_accepted(support.request(created_at="2028-02-29T23:59:59.999Z"))
        self.check_rejections(
            [
                (mutated("created_at", value), ErrorCode.INVALID_TIME, "created_at")
                for value in (
                    "2026-11-04T09:12:44.123+00:00",
                    "2026-11-04 09:12:44Z",
                    "2026-11-04T09:12:44.12Z",
                    "2026-11-04T09:12:44.123456Z",
                    "2026-11-04t09:12:44z",
                    "2026-13-04T09:12:44Z",
                    "2026-02-29T09:12:44Z",
                    "2026-11-04T24:00:00Z",
                    "2026-11-04T09:12:60Z",
                    "\uff12\uff10\uff12\uff16-11-04T09:12:44Z",  # Fullwidth digits match \d.
                    1_730_000_000,
                    None,
                )
            ]
        )

    def test_page_is_an_object(self) -> None:
        self.check_rejections(
            [
                (mutated("page", value), ErrorCode.INVALID_PAGE, "page")
                for value in ([], "https://example.org/", None)
            ]
        )

    def test_url_is_bounded_text_without_control_characters(self) -> None:
        self.assert_accepted(support.request(page={**support.golden()["page"], "url": "a" * 8192}))
        self.assert_accepted(support.request(page={**support.golden()["page"], "url": "é" * 4096}))
        self.check_rejections(
            [
                (mutated("page.url", value), ErrorCode.INVALID_URL, "page.url")
                for value in (
                    "",
                    "a" * 8193,
                    "é" * 4097,  # 4097 characters, 8194 UTF-8 bytes.
                    "https://example.org/\x00",
                    "https://example.org/\n",
                    "https://example.org/\x7f",
                    "https://example.org/\x85",  # C1.
                    "https://example.org/\ud800",  # Lone surrogate.
                    5,
                    None,
                )
            ]
        )

    def test_title_is_null_or_bounded_text_without_control_characters(self) -> None:
        self.assert_accepted(support.request(page={**support.golden()["page"], "title": None}))
        self.assert_accepted(
            support.request(page={**support.golden()["page"], "title": "t" * 4096})
        )
        self.check_rejections(
            [
                (mutated("page.title", value), ErrorCode.INVALID_TITLE, "page.title")
                for value in ("t" * 4097, "two\nlines", "\x1b[31mred", "\x9b", 1, ["title"])
            ]
        )

    def test_display_is_page_or_reader(self) -> None:
        self.assert_accepted(
            support.request(page={**support.golden()["page"], "display": "reader"})
        )
        self.check_rejections(
            [
                (mutated("page.display", value), ErrorCode.INVALID_DISPLAY, "page.display")
                for value in ("Reader", "", None, ["page"], {"page": True})
            ]
        )

    def test_page_flags_are_booleans(self) -> None:
        self.check_rejections(
            [
                (mutated(f"page.{flag}", value), ErrorCode.INVALID_FLAG, f"page.{flag}")
                for flag in ("url_edited", "load_failed", "crashed")
                for value in ("false", 0, None)
            ]
        )

    def test_comment_is_null_or_bounded_text_allowing_tab_and_newline(self) -> None:
        for value in (None, "", "first line\n\tsecond line", "c" * 8000):
            with self.subTest(value=value if value is None else len(value)):
                self.assert_accepted(support.request(comment=value))
        self.check_rejections(
            [
                (mutated("comment", value), ErrorCode.INVALID_COMMENT, "comment")
                for value in ("c" * 8001, "carriage\rreturn", "\x1b[2J", "\x00", "\udfff", 42)
            ]
        )

    def test_client_entries_are_bounded_plain_values(self) -> None:
        client = support.golden()["client"]
        full = {**client, **{f"extra_{index}": index for index in range(24 - len(client))}}
        self.assert_accepted(support.request(client=full))
        self.assert_accepted(
            support.request(
                client={**client, "flag": True, "big": -(2**63), "ratio": 0.5, "k" * 32: "v" * 256}
            )
        )
        too_many = {**full, "one_more": 1}
        self.check_rejections(
            [
                (mutated("client", []), ErrorCode.INVALID_CLIENT, "client"),
                (mutated("client", None), ErrorCode.INVALID_CLIENT, "client"),
                (mutated("client", too_many), ErrorCode.INVALID_CLIENT, "client"),
                (mutated("client.App", "x"), ErrorCode.INVALID_CLIENT, "client"),
                (mutated("client.k-1", "x"), ErrorCode.INVALID_CLIENT, "client"),
                (mutated("client." + "k" * 33, "x"), ErrorCode.INVALID_CLIENT, "client"),
                (mutated("client.extra", [1]), ErrorCode.INVALID_CLIENT, "client.extra"),
                (mutated("client.extra", {}), ErrorCode.INVALID_CLIENT, "client.extra"),
                (mutated("client.extra", None), ErrorCode.INVALID_CLIENT, "client.extra"),
                (
                    mutated("client.build_type", "d" * 257),
                    ErrorCode.INVALID_CLIENT,
                    "client.build_type",
                ),
                (
                    mutated("client.engine", "servo\x1b[0m"),
                    ErrorCode.INVALID_CLIENT,
                    "client.engine",
                ),
                (mutated("client.app_build", 2**63), ErrorCode.INVALID_CLIENT, "client.app_build"),
                (removed("client.app_version"), ErrorCode.INVALID_CLIENT, "client.app_version"),
                (mutated("client.app", ""), ErrorCode.INVALID_CLIENT, "client.app"),
                (
                    mutated("client.android_release", 15),
                    ErrorCode.INVALID_CLIENT,
                    "client.android_release",
                ),
            ]
        )

    def test_exactly_one_of_screenshot_and_reason(self) -> None:
        self.check_rejections(
            [
                (mutated("screenshot", None), ErrorCode.INVALID_SCREENSHOT, "screenshot"),
                (mutated("screenshot_omitted", "user"), ErrorCode.INVALID_SCREENSHOT, "screenshot"),
                (mutated("screenshot", []), ErrorCode.INVALID_SCREENSHOT, "screenshot"),
            ]
        )
        for reason in ("other", "USER", 3, ["user"]):
            with self.subTest(reason=reason):
                request = support.without_screenshot(support.golden())
                request["screenshot_omitted"] = reason
                self.assert_request_rejected(
                    request, ErrorCode.INVALID_SCREENSHOT, "screenshot_omitted"
                )

    def test_media_type_is_png(self) -> None:
        self.check_rejections(
            [
                (
                    mutated("screenshot.media_type", value),
                    ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                    "screenshot.media_type",
                )
                for value in ("image/jpeg", "image/PNG", None)
            ]
        )

    def test_width_and_height_are_integers_up_to_8192(self) -> None:
        self.check_rejections(
            [
                (
                    mutated(f"screenshot.{side}", value),
                    ErrorCode.INVALID_SCREENSHOT,
                    f"screenshot.{side}",
                )
                for side in ("width", "height")
                for value in (0, -1, 8193, "3", True, 3.0, None)
            ]
        )

    def test_scaled_is_a_boolean(self) -> None:
        self.check_rejections(
            [
                (
                    mutated("screenshot.scaled", "no"),
                    ErrorCode.INVALID_SCREENSHOT,
                    "screenshot.scaled",
                )
            ]
        )

    def test_sha256_is_lowercase_hex(self) -> None:
        digest = support.golden()["screenshot"]["sha256"]
        self.check_rejections(
            [
                (
                    mutated("screenshot.sha256", value),
                    ErrorCode.INVALID_SCREENSHOT,
                    "screenshot.sha256",
                )
                for value in (digest.upper(), digest[:-1], digest + "0", None)
            ]
        )

    def test_bytes_is_a_positive_integer_matching_the_data(self) -> None:
        size = support.golden()["screenshot"]["bytes"]
        self.check_rejections(
            [
                (mutated("screenshot.bytes", 0), ErrorCode.INVALID_SCREENSHOT, "screenshot.bytes"),
                (
                    mutated("screenshot.bytes", str(size)),
                    ErrorCode.INVALID_SCREENSHOT,
                    "screenshot.bytes",
                ),
                (
                    mutated("screenshot.bytes", True),
                    ErrorCode.INVALID_SCREENSHOT,
                    "screenshot.bytes",
                ),
                (
                    mutated("screenshot.bytes", size - 1),
                    ErrorCode.INVALID_SCREENSHOT,
                    "screenshot.data",
                ),
                (
                    mutated("screenshot.bytes", size + 1),
                    ErrorCode.INVALID_SCREENSHOT,
                    "screenshot.data",
                ),
            ]
        )

    def test_a_screenshot_over_the_limit_is_413_before_decoding(self) -> None:
        request = support.golden()

        with self.assertRaises(ValidationError) as caught:
            validation.parse_report(support.encode(request), max_screenshot_bytes=len(PNG) - 1)

        error = caught.exception
        self.assertEqual(
            (error.code, error.field, error.status),
            (ErrorCode.SCREENSHOT_TOO_LARGE, "screenshot.bytes", 413),
        )
        # Exactly at the limit is accepted.
        validation.parse_report(support.encode(request), max_screenshot_bytes=len(PNG))

    def test_data_is_standard_padded_base64_without_whitespace(self) -> None:
        unsafe = png_with_url_unsafe_base64()
        standard = support.with_screenshot(support.golden(), unsafe, 3, 2)
        encoded = standard["screenshot"]["data"]
        self.assert_accepted(standard)
        padded = support.with_screenshot(support.golden(), support.png(1, 1), 1, 1)
        self.assertTrue(padded["screenshot"]["data"].endswith("="))
        self.assert_accepted(padded)

        cases = {
            "url-safe alphabet": encoded.replace("+", "-").replace("/", "_"),
            "space in place of a character": " " + encoded[1:],
            "line break in place of a character": encoded[:40] + "\n" + encoded[41:],
            "tab in place of a character": encoded[:-1] + "\t",
            "non-ASCII character": "é" + encoded[1:],
            "padding inside": "====" + encoded[4:],
        }
        for name, data in cases.items():
            with self.subTest(name):
                request = copy.deepcopy(standard)
                request["screenshot"]["data"] = data
                error = self.assert_request_rejected(
                    request, ErrorCode.INVALID_SCREENSHOT, "screenshot.data"
                )
                self.assertEqual(error.detail, "not standard padded base64")

        padded_data = padded["screenshot"]["data"]
        length_cases = {
            "inserted line break": encoded[:40] + "\n" + encoded[40:],
            "inserted space": encoded + " ",
            "missing padding": padded_data.rstrip("="),
            "MIME line wrapping": base64.encodebytes(unsafe).decode("ascii"),
        }
        for name, data in length_cases.items():
            with self.subTest(name):
                request = copy.deepcopy(padded if name == "missing padding" else standard)
                request["screenshot"]["data"] = data
                self.assert_request_rejected(
                    request, ErrorCode.INVALID_SCREENSHOT, "screenshot.data"
                )

        request = copy.deepcopy(standard)
        request["screenshot"]["data"] = 12
        self.assert_request_rejected(request, ErrorCode.INVALID_SCREENSHOT, "screenshot.data")

    def test_sha256_must_match_the_data(self) -> None:
        request = support.golden()
        request["screenshot"]["sha256"] = hashlib.sha256(b"other").hexdigest()

        self.assert_request_rejected(
            request, ErrorCode.SCREENSHOT_DIGEST_MISMATCH, "screenshot.sha256"
        )

    def test_png_structure_is_checked_after_the_digest(self) -> None:
        cases = {
            "trailing byte": PNG + b"\x00",
            "JPEG": b"\xff\xd8\xff\xe0" + bytes(80),
            "declared size differs": support.png(2, 3),
        }
        for name, data in cases.items():
            with self.subTest(name):
                request = support.with_screenshot(support.golden(), data, 3, 2)
                self.assert_request_rejected(request, ErrorCode.INVALID_PNG, "screenshot.data")


class PngStructureTest(unittest.TestCase):
    """check_png reads chunk headers only (design 1.4 and 1.8)."""

    def assert_png_rejected(self, data: bytes, width: int, height: int, detail: str) -> None:
        with self.assertRaises(ValidationError) as caught:
            validation.check_png(data, width, height)
        error = caught.exception
        self.assertEqual((error.code, error.field), (ErrorCode.INVALID_PNG, "screenshot.data"))
        self.assertEqual(error.detail, detail)

    def build(self, *parts: bytes, width: int = 3, height: int = 2, **ihdr: int) -> bytes:
        return validation.PNG_SIGNATURE + support.ihdr(width, height, **ihdr) + b"".join(parts)

    def idat(self, width: int = 3, height: int = 2) -> bytes:
        return support.rgba_idat(width, height, support.gradient)

    def test_valid_pngs_pass(self) -> None:
        validation.check_png(PNG, 3, 2)
        validation.check_png(support.png(8192, 1), 8192, 1)
        validation.check_png(self.build(self.idat(), support.chunk(b"IEND"), interlace=1), 3, 2)
        validation.check_png(
            self.build(
                support.chunk(b"tEXt", b"k\x00v"), self.idat(), self.idat(), support.chunk(b"IEND")
            ),
            3,
            2,
        )
        for colour, depths in {
            0: (1, 2, 4, 8, 16),
            2: (8, 16),
            3: (1, 2, 4, 8),
            4: (8, 16),
            6: (8, 16),
        }.items():
            for depth in depths:
                with self.subTest(colour=colour, depth=depth):
                    data = self.build(
                        self.idat(), support.chunk(b"IEND"), colour_type=colour, bit_depth=depth
                    )
                    validation.check_png(data, 3, 2)

    def test_signature(self) -> None:
        self.assert_png_rejected(b"", 3, 2, "bad signature")
        self.assert_png_rejected(b"\x89PNG\r\n\x1a", 3, 2, "bad signature")
        self.assert_png_rejected(b"\x89PNG\n\r\x1a\n" + PNG[8:], 3, 2, "bad signature")

    def test_ihdr_size_must_equal_the_declared_size(self) -> None:
        self.assert_png_rejected(PNG, 2, 3, "size differs from the declared size")
        self.assert_png_rejected(PNG, 3, 3, "size differs from the declared size")

    def test_sides_are_1_to_8192(self) -> None:
        wide = self.build(self.idat(1, 1), support.chunk(b"IEND"), width=8193, height=1)
        self.assert_png_rejected(wide, 8193, 1, "side out of 1 to 8192")
        tall = self.build(self.idat(1, 1), support.chunk(b"IEND"), width=1, height=8193)
        self.assert_png_rejected(tall, 1, 8193, "side out of 1 to 8192")
        empty = self.build(self.idat(1, 1), support.chunk(b"IEND"), width=0, height=1)
        self.assert_png_rejected(empty, 0, 1, "side out of 1 to 8192")

    def test_first_chunk_is_a_13_byte_ihdr(self) -> None:
        idat_first = (
            validation.PNG_SIGNATURE + self.idat() + support.ihdr(3, 2) + support.chunk(b"IEND")
        )
        self.assert_png_rejected(idat_first, 3, 2, "first chunk is not IHDR")
        for size in (12, 14):
            with self.subTest(size=size):
                short = validation.PNG_SIGNATURE + support.chunk(b"IHDR", bytes(size))
                self.assert_png_rejected(
                    short + self.idat() + support.chunk(b"IEND"), 3, 2, "IHDR length is not 13"
                )
        twice = self.build(support.ihdr(3, 2), self.idat(), support.chunk(b"IEND"))
        self.assert_png_rejected(twice, 3, 2, "second IHDR chunk")

    def test_bit_depth_and_colour_type_form_a_valid_combination(self) -> None:
        for colour, depth in ((2, 4), (6, 1), (3, 16), (0, 3), (5, 8), (1, 8)):
            with self.subTest(colour=colour, depth=depth):
                data = self.build(
                    self.idat(), support.chunk(b"IEND"), colour_type=colour, bit_depth=depth
                )
                self.assert_png_rejected(data, 3, 2, "invalid bit depth and colour type")

    def test_compression_filter_and_interlace_methods(self) -> None:
        end = support.chunk(b"IEND")
        self.assert_png_rejected(
            self.build(self.idat(), end, compression=1), 3, 2, "unknown compression method"
        )
        self.assert_png_rejected(
            self.build(self.idat(), end, filter_method=1), 3, 2, "unknown filter method"
        )
        self.assert_png_rejected(
            self.build(self.idat(), end, interlace=2), 3, 2, "unknown interlace method"
        )

    def test_crc_is_checked(self) -> None:
        bad_idat = support.chunk(b"IDAT", b"\x78\x9c", crc=0)
        self.assert_png_rejected(
            self.build(bad_idat, support.chunk(b"IEND")), 3, 2, "chunk CRC mismatch"
        )
        bad_ihdr = bytearray(PNG)
        bad_ihdr[20] ^= 0x01  # A height byte; the IHDR CRC no longer matches.
        self.assert_png_rejected(bytes(bad_ihdr), 3, 2, "chunk CRC mismatch")

    def test_chunk_types_are_four_ascii_letters(self) -> None:
        for kind in (b"ID4T", b"ID T", b"ID\xc0T"):
            with self.subTest(kind=kind):
                data = self.build(support.chunk(kind, b"x"), self.idat(), support.chunk(b"IEND"))
                self.assert_png_rejected(data, 3, 2, "chunk type is not four ASCII letters")

    def test_chunk_length_is_at_most_2_31_minus_1(self) -> None:
        header = (2**31).to_bytes(4, "big") + b"tEXt"
        self.assert_png_rejected(self.build(header + bytes(8)), 3, 2, "chunk length over 2^31-1")

    def test_a_chunk_past_the_end_of_the_buffer(self) -> None:
        idat = self.idat()
        self.assert_png_rejected(
            self.build(idat[:-5]), 3, 2, "chunk extends past the end of the data"
        )
        oversized = (1000).to_bytes(4, "big") + b"IDAT" + bytes(20)
        self.assert_png_rejected(
            self.build(oversized), 3, 2, "chunk extends past the end of the data"
        )

    def test_a_truncated_chunk(self) -> None:
        self.assert_png_rejected(PNG[:-6], 3, 2, "truncated chunk")
        self.assert_png_rejected(PNG[:-1], 3, 2, "truncated chunk")
        self.assert_png_rejected(validation.PNG_SIGNATURE + b"\x00\x00", 3, 2, "truncated chunk")

    def test_at_least_one_idat(self) -> None:
        self.assert_png_rejected(self.build(support.chunk(b"IEND")), 3, 2, "no IDAT chunk")

    def test_iend_is_last_empty_and_followed_by_nothing(self) -> None:
        self.assert_png_rejected(self.build(self.idat()), 3, 2, "no IEND chunk")
        self.assert_png_rejected(PNG + b"\x00", 3, 2, "data after IEND")
        self.assert_png_rejected(PNG + support.chunk(b"tEXt", b"k\x00v"), 3, 2, "data after IEND")
        self.assert_png_rejected(
            self.build(self.idat(), support.chunk(b"IEND", b"x")), 3, 2, "IEND chunk has data"
        )

    def test_at_most_10000_chunks(self) -> None:
        text = support.chunk(b"tEXt", b"k\x00v")
        at_limit = self.build(self.idat(), text * 9_997, support.chunk(b"IEND"))
        validation.check_png(at_limit, 3, 2)

        over = self.build(self.idat(), text * 9_998, support.chunk(b"IEND"))
        self.assert_png_rejected(over, 3, 2, "too many chunks")


class JsonDecodingTest(ValidationTestCase):
    def test_duplicate_keys_are_refused_at_any_depth(self) -> None:
        compact = support.encode(support.golden())
        cases = {
            "top level": compact.replace(b'"comment":', b'"comment":null,"comment":', 1),
            "page": compact.replace(b'"page":{', b'"page":{"title":"x",', 1),
            "client": compact.replace(b'"client":{', b'"client":{"app":"other",', 1),
            "screenshot": compact.replace(b'"screenshot":{', b'"screenshot":{"scaled":true,', 1),
        }
        for name, body in cases.items():
            with self.subTest(name):
                self.assertNotEqual(body, compact)
                self.assert_rejected(body, ErrorCode.DUPLICATE_KEY, None)

    def test_the_body_must_be_utf8(self) -> None:
        compact = support.encode(support.golden())
        cases = {
            "Latin-1": compact.replace(b"Example article", "Exemple aé".encode("latin-1")),
            "encoded surrogate": compact.replace(b"Example article", b"\xed\xa0\x80"),
            "overlong": compact.replace(b"Example article", b"\xc0\xaf"),
            "UTF-16": json.dumps(support.golden()).encode("utf-16"),
        }
        for name, body in cases.items():
            with self.subTest(name):
                error = self.assert_rejected(body, ErrorCode.INVALID_JSON, None)
                self.assertEqual(error.detail, "body is not UTF-8")

    def test_a_byte_order_mark_is_refused(self) -> None:
        self.assert_rejected(b"\xef\xbb\xbf" + support.golden_bytes(), ErrorCode.INVALID_JSON, None)

    def test_non_finite_and_oversized_numbers_are_refused(self) -> None:
        compact = support.encode(support.golden())
        for literal in (b"NaN", b"Infinity", b"-Infinity", b"1e999", b"1" * 5000):
            with self.subTest(literal=literal[:12]):
                body = compact.replace(b'"density":2.625', b'"density":' + literal)
                self.assert_rejected(body, ErrorCode.INVALID_JSON, None)

    def test_malformed_and_non_object_bodies_are_refused(self) -> None:
        for body in (b"", b"{", b"{} x", b"[]", b'"text"', b"12", b"null", b"{'id': 1}"):
            with self.subTest(body=body):
                self.assert_rejected(body, ErrorCode.INVALID_JSON, None)

    def test_deep_nesting_is_refused(self) -> None:
        body = b'{"a":' * 50_000 + b"1" + b"}" * 50_000
        self.assert_rejected(body, ErrorCode.INVALID_JSON, None)

    def test_a_body_with_too_many_values_is_refused_before_parsing(self) -> None:
        body = b'{"client":[' + b"{}," * 70_000 + b"{}]}"
        error = self.assert_rejected(body, ErrorCode.INVALID_JSON, None)
        self.assertEqual(error.detail, "too many JSON values")


class StatusChangeTest(unittest.TestCase):
    def assert_change_rejected(self, body: bytes, code: ErrorCode, field: str | None) -> None:
        with self.assertRaises(ValidationError) as caught:
            validation.parse_status_change(body)
        error = caught.exception
        self.assertEqual((error.code, error.field, error.status), (code, field, 400))

    def test_valid_changes(self) -> None:
        self.assertEqual(
            validation.parse_status_change(
                b'{"status":"claimed","expect":"open","note":"paseo: investigating"}'
            ),
            validation.StatusChange(Status.CLAIMED, Status.OPEN, "paseo: investigating"),
        )
        self.assertEqual(
            validation.parse_status_change(b'{"status":"done"}'),
            validation.StatusChange(Status.DONE),
        )
        self.assertEqual(
            validation.parse_status_change(b'{"status":"open","expect":null,"note":null}'),
            validation.StatusChange(Status.OPEN),
        )
        change = validation.parse_status_change(
            json.dumps({"status": "rejected", "note": "n\n\t" + "x" * 1997}).encode()
        )
        self.assertIs(change.status, Status.REJECTED)
        self.assertEqual(len(change.note), 2000)

    def test_invalid_changes(self) -> None:
        cases = [
            (b"[]", ErrorCode.INVALID_JSON, None),
            (b'{"status":"done","status":"open"}', ErrorCode.DUPLICATE_KEY, None),
            (b'{"status":"done","lease":60}', ErrorCode.UNKNOWN_FIELD, "lease"),
            (b'{"expect":"open"}', ErrorCode.MISSING_FIELD, "status"),
            (b'{"status":"deleted"}', ErrorCode.INVALID_STATUS, "status"),
            (b'{"status":"OPEN"}', ErrorCode.INVALID_STATUS, "status"),
            (b'{"status":null}', ErrorCode.INVALID_STATUS, "status"),
            (b'{"status":["open"]}', ErrorCode.INVALID_STATUS, "status"),
            (b'{"status":"done","expect":"deleted"}', ErrorCode.INVALID_STATUS, "expect"),
            (b'{"status":"done","expect":1}', ErrorCode.INVALID_STATUS, "expect"),
            (b'{"status":"done","note":5}', ErrorCode.INVALID_NOTE, "note"),
            (b'{"status":"done","note":"\\u0000"}', ErrorCode.INVALID_NOTE, "note"),
            (b'{"status":"done","note":"\\r"}', ErrorCode.INVALID_NOTE, "note"),
            (
                json.dumps({"status": "done", "note": "x" * 2001}).encode(),
                ErrorCode.INVALID_NOTE,
                "note",
            ),
            (b'{"status":"done","note":"\xff"}', ErrorCode.INVALID_JSON, None),
        ]
        for body, code, field in cases:
            with self.subTest(body=body[:40]):
                self.assert_change_rejected(body, code, field)


if __name__ == "__main__":
    unittest.main()
