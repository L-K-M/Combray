"""Tests for sitecheck.images. The Pillow-backed tests skip when Pillow is not installed."""

from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path
from typing import Any
from unittest import mock

from sitecheck import compare, images
from sitecheck.model import Engine, PageResult, PageStatus, VerdictKind

HAS_PILLOW = importlib.util.find_spec("PIL") is not None
SIZE = (412, 915)
WHITE = (255, 255, 255)
BLACK = (0, 0, 0)


class WithoutPillowTest(unittest.TestCase):
    """Runs everywhere: the error a missing Pillow produces."""

    def test_missing_pillow_raises_an_actionable_error(self) -> None:
        # A None entry in sys.modules makes the import fail like a missing package.
        with mock.patch.dict(sys.modules, {"PIL": None}):
            self.assertFalse(images.available())
            with self.assertRaises(images.ImagesUnavailable) as raised:
                images.stats(Path("unused.png"))
            self.assertIn("uv sync", str(raised.exception))
            with self.assertRaises(images.ImagesUnavailable):
                images.compare_screenshots(Path("a.png"), Path("b.png"))


@unittest.skipUnless(HAS_PILLOW, "Pillow is not installed")
class ImagesTest(unittest.TestCase):
    def setUp(self) -> None:
        self._dir = tempfile.TemporaryDirectory()
        self.addCleanup(self._dir.cleanup)
        self.dir = Path(self._dir.name)

    def png(
        self,
        name: str,
        draw: Any = None,
        *,
        mode: str = "RGB",
        color: Any = WHITE,
        size: tuple[int, int] = SIZE,
    ) -> Path:
        from PIL import Image, ImageDraw

        image = Image.new(mode, size, color)
        if draw is not None:
            draw(ImageDraw.Draw(image))
        path = self.dir / name
        image.save(path)
        return path

    def stripes(self, name: str) -> Path:
        """Black bars on white, like lines of text."""

        def draw(canvas: Any) -> None:
            for top in range(0, SIZE[1], 40):
                canvas.rectangle((16, top, SIZE[0] - 16, top + 19), fill=BLACK)

        return self.png(name, draw)

    def test_stats_of_a_uniform_screenshot(self) -> None:
        result = images.stats(self.png("white.png"))

        self.assertEqual(result["width"], 412)
        self.assertEqual(result["height"], 915)
        self.assertEqual(result["mean"], 255.0)
        self.assertEqual(result["stddev"], 0.0)
        self.assertEqual(result["distinct_colors_sample"], 1)

    def test_stats_of_a_screenshot_with_content(self) -> None:
        result = images.stats(self.stripes("stripes.png"))

        self.assertGreater(result["stddev"], 10.0)
        self.assertLess(result["mean"], 255.0)
        self.assertEqual(result["distinct_colors_sample"], 2)

    def test_transparent_pixels_count_as_white(self) -> None:
        path = self.png("clear.png", mode="RGBA", color=(0, 0, 0, 0))
        result = images.stats(path)

        self.assertEqual(result["mean"], 255.0)
        self.assertEqual(result["stddev"], 0.0)

    def test_identical_screenshots_are_fully_similar(self) -> None:
        result = images.similarity(self.stripes("a.png"), self.stripes("b.png"))
        self.assertEqual(result, {"pixel": 1.0, "ahash": 1.0, "score": 1.0})

    def test_opposite_screenshots_are_not_similar(self) -> None:
        white = self.png("white.png")
        black = self.png("black.png", color=BLACK)
        result = images.similarity(white, black)

        self.assertEqual(result["pixel"], 0.0)
        # Both hashes are all zero bits: the hash cannot see a uniform change.
        self.assertEqual(result["ahash"], 1.0)
        self.assertEqual(result[images.SIMILARITY_SCORE], 0.0)

    def test_score_is_the_lower_measure(self) -> None:
        def block_at(top: int) -> Any:
            def draw(canvas: Any) -> None:
                canvas.rectangle((0, top, SIZE[0], top + 200), fill=BLACK)

            return draw

        result = images.similarity(
            self.png("top.png", block_at(0)), self.png("low.png", block_at(600))
        )

        self.assertLess(result["ahash"], result["pixel"])
        self.assertEqual(result["score"], result["ahash"])

    def test_different_sizes_still_compare(self) -> None:
        small = self.png("small.png", size=(206, 457))
        result = images.similarity(self.png("white.png"), small)
        self.assertEqual(result["pixel"], 1.0)

    def test_partial_difference_is_between_the_extremes(self) -> None:
        def top_half_black(canvas: Any) -> None:
            canvas.rectangle((0, 0, SIZE[0], SIZE[1] // 2), fill=BLACK)

        result = images.similarity(self.png("white.png"), self.png("half.png", top_half_black))
        self.assertGreater(result["pixel"], 0.4)
        self.assertLess(result["pixel"], 0.6)

    def test_unreadable_screenshots_raise_screenshot_error(self) -> None:
        garbage = self.dir / "garbage.png"
        garbage.write_bytes(b"not a png")
        for path in (garbage, self.dir / "missing.png"):
            with self.subTest(path=path.name), self.assertRaises(images.ScreenshotError):
                images.stats(path)

    def test_compare_screenshots_reports_missing_and_unreadable_images(self) -> None:
        garbage = self.dir / "garbage.png"
        garbage.write_bytes(b"not a png")
        result = images.compare_screenshots(None, garbage)

        self.assertIsNone(result["servo"])
        self.assertIsNone(result["reference"])
        self.assertIsNone(result["similarity"])
        self.assertEqual(len(result["errors"]), 2)
        self.assertTrue(result["errors"][0].startswith("servo:"))
        self.assertTrue(result["errors"][1].startswith("reference:"))

    def test_compare_screenshots_feeds_the_blank_rule(self) -> None:
        servo_png = self.png("servo.png")
        reference_png = self.stripes("reference.png")
        evidence = images.compare_screenshots(servo_png, reference_png)

        self.assertEqual(evidence["errors"], [])
        self.assertIsNotNone(evidence["similarity"])
        metrics = {"text": "", "textLength": 500, "scrollWidth": 412, "innerWidth": 412}
        url = "https://example.com/"
        verdict = compare.verdict(
            PageResult(Engine.SERVO, url, PageStatus.OK, 1, metrics=dict(metrics)),
            PageResult(Engine.CHROMIUM, url, PageStatus.OK, 1, metrics=dict(metrics)),
            evidence,
        )
        self.assertEqual(verdict.kind, VerdictKind.BLANK)


if __name__ == "__main__":
    unittest.main()
