"""Screenshot statistics and similarity for the comparison.

Everything is computed on small grayscale thumbnails. The verdict rules only ask
whether a screenshot is near-uniform (a blank page) and whether two screenshots are
grossly different, and thumbnails answer that cheaply while ignoring font
antialiasing and other sub-pixel differences between the engines.

Pillow is imported inside the functions so that the pure modules, and the test
suite on a Python without third-party packages, import this package unharmed.
"""

from __future__ import annotations

from pathlib import Path
from typing import TYPE_CHECKING, Any

if TYPE_CHECKING:
    from PIL.Image import Image

# Width of the thumbnail the statistics use; the height keeps the aspect ratio
# (a 412x915 viewport becomes 64x142).
_STATS_WIDTH = 64

# Both screenshots are scaled to this fixed size before they are compared, so
# captures of slightly different sizes still compare. It keeps the viewport's
# aspect ratio.
_SIMILARITY_SIZE = (64, 142)

# The average hash is computed on an 8x8 grid: 64 bits.
_AHASH_SIZE = 8

_MAX_GRAY = 255
_WHITE = (255, 255, 255, 255)

# The key of the similarity value `compare.verdict` applies its threshold to: the
# lower of the two measures, so a change in brightness (pixel) and a change in
# structure (ahash) both count. Neither measure suffices alone. On real
# screenshots (2026-10-24: example.com, a Wikipedia article, a local fixture),
# unrelated near-white pages still scored 0.89-0.92 on the pixel measure but
# 0.48-0.67 on the hash, while the hash does not see a light page turning
# uniformly dark, which the pixel measure scores near 0. The same page shifted
# down by 8 or 24 px kept a hash similarity of 0.97 and 0.91.
SIMILARITY_SCORE = "score"


class ImagesUnavailable(RuntimeError):
    """Pillow is not installed in the running environment."""


class ScreenshotError(Exception):
    """A screenshot file could not be read as an image."""


def available() -> bool:
    """Whether Pillow can be imported, so callers can report it before a run."""
    try:
        _pillow()
    except ImagesUnavailable:
        return False
    return True


def stats(path: Path) -> dict[str, Any]:
    """Size and brightness statistics of one screenshot.

    Returns ``{"width", "height", "mean", "stddev", "distinct_colors_sample"}``:
    the original size in pixels, the mean and standard deviation of the
    grayscale thumbnail (0-255), and the number of distinct RGB colours in a
    nearest-neighbour thumbnail (actual page colours, no blending).
    """
    image_module, image_stat, _ = _pillow()
    rgb = _load_rgb(path)
    width, height = rgb.size
    thumb_size = (_STATS_WIDTH, max(1, round(height * _STATS_WIDTH / width)))

    gray = rgb.convert("L").resize(thumb_size, image_module.Resampling.BOX)
    gray_stats = image_stat.Stat(gray)

    sample = rgb.resize(thumb_size, image_module.Resampling.NEAREST)
    # maxcolors equal to the pixel count makes getcolors() always return a list.
    colors = sample.getcolors(maxcolors=sample.width * sample.height)
    assert colors is not None

    return {
        "width": width,
        "height": height,
        "mean": round(gray_stats.mean[0], 3),
        "stddev": round(gray_stats.stddev[0], 3),
        "distinct_colors_sample": len(colors),
    }


def similarity(a: Path, b: Path) -> dict[str, float]:
    """How alike two screenshots look, from 0 (unrelated) to 1 (identical).

    Returns ``{"pixel": ..., "ahash": ..., "score": ...}``:
    - pixel: 1 minus the mean absolute difference of 64x142 grayscale
      thumbnails, normalised to 0-1. Sees brightness and colour changes, but
      two different mostly-white pages still score high.
    - ahash: share of equal bits of 8x8 average hashes (a bit is set where a
      cell is brighter than the image's mean). Sees where content is and
      tolerates small shifts, but not a uniform change in brightness.
    - score: the lower of the two; the value the verdict uses
      (`SIMILARITY_SCORE`).
    """
    image_module, image_stat, image_chops = _pillow()
    gray_a = _load_rgb(a).convert("L").resize(_SIMILARITY_SIZE, image_module.Resampling.BOX)
    gray_b = _load_rgb(b).convert("L").resize(_SIMILARITY_SIZE, image_module.Resampling.BOX)

    difference = image_stat.Stat(image_chops.difference(gray_a, gray_b)).mean[0]
    pixel = 1.0 - difference / _MAX_GRAY

    hash_a = _average_hash(gray_a)
    hash_b = _average_hash(gray_b)
    equal_bits = sum(1 for bit_a, bit_b in zip(hash_a, hash_b, strict=True) if bit_a == bit_b)
    ahash = equal_bits / len(hash_a)

    pixel = round(pixel, 4)
    ahash = round(ahash, 4)
    return {"pixel": pixel, "ahash": ahash, SIMILARITY_SCORE: min(pixel, ahash)}


def compare_screenshots(servo: Path | None, reference: Path | None) -> dict[str, Any]:
    """The image evidence `compare.verdict` takes as its `img` argument.

    Returns ``{"servo": stats | None, "reference": stats | None,
    "similarity": similarity | None, "errors": [str, ...]}``. A missing or
    unreadable screenshot yields None for its entries plus a line in "errors"
    (so the run records why the image rules were skipped) instead of failing
    the page. Raises ImagesUnavailable when Pillow is missing, because then no
    page in the run can be compared.
    """
    errors: list[str] = []
    servo_stats = _stats_or_error("servo", servo, errors)
    reference_stats = _stats_or_error("reference", reference, errors)

    pair_similarity = None
    if servo_stats is not None and reference_stats is not None:
        assert servo is not None and reference is not None
        pair_similarity = similarity(servo, reference)

    return {
        "servo": servo_stats,
        "reference": reference_stats,
        "similarity": pair_similarity,
        "errors": errors,
    }


def _stats_or_error(engine: str, path: Path | None, errors: list[str]) -> dict[str, Any] | None:
    if path is None:
        errors.append(f"{engine}: no screenshot")
        return None

    try:
        return stats(path)
    except ScreenshotError as error:
        errors.append(f"{engine}: {error}")
        return None


def _average_hash(gray: Image) -> list[bool]:
    image_module, _, _ = _pillow()
    cells = gray.resize((_AHASH_SIZE, _AHASH_SIZE), image_module.Resampling.BOX)
    # A mode "L" image's bytes are its pixel values, one byte each.
    values = list(cells.tobytes())
    mean = sum(values) / len(values)
    return [value > mean for value in values]


def _load_rgb(path: Path) -> Image:
    """Open a screenshot as RGB, flattening any transparency onto white.

    Servo's screenshots are RGBA; a transparent pixel is what an unpainted page
    shows over the browser's white background.
    """
    image_module, _, _ = _pillow()
    try:
        with image_module.open(path) as opened:
            opened.load()
            image = opened
    except (OSError, ValueError, image_module.DecompressionBombError) as error:
        # OSError covers a missing file and Pillow's UnidentifiedImageError; a
        # DecompressionBombError, a declared size Pillow refuses to decode.
        raise ScreenshotError(f"cannot read {path.name}: {error}") from error

    if image.width == 0 or image.height == 0:
        raise ScreenshotError(f"{path.name} has no pixels")

    if image.mode in ("RGBA", "LA", "PA") or "transparency" in image.info:
        rgba = image.convert("RGBA")
        background = image_module.new("RGBA", rgba.size, _WHITE)
        return image_module.alpha_composite(background, rgba).convert("RGB")
    return image.convert("RGB")


def _pillow() -> tuple[Any, Any, Any]:
    try:
        from PIL import Image, ImageChops, ImageStat
    except ImportError as error:
        raise ImagesUnavailable(
            "Pillow is not installed in this Python environment; run the site check "
            "through its uv project (uv sync --project testing/sitecheck)"
        ) from error
    return Image, ImageStat, ImageChops
