"""Fetches the PDF probe's third-party inputs into testing/fixtures/pdfprobe/vendor/ (git-ignored).

    python3 -I testing/fixtures/pdfprobe/fetch.py [--cache DIR]

Every download is checked against the hash pinned here before anything is unpacked. With
--cache, files already in DIR (named as in the URL) are used instead of downloading them.

Inputs: pdfjs-dist 6.4.299 and @embedpdf/pdfium 2.15.1 from the npm registry, and pdf.js test
corpus files from mozilla/pdf.js at a fixed commit. None of this is committed: the probe is an
experiment on a draft PR (docs/PDF.md, phase 0b).
"""

import argparse
import base64
import hashlib
import io
import pathlib
import shutil
import sys
import tarfile
import urllib.request

HERE = pathlib.Path(__file__).resolve().parent
VENDOR = HERE / "vendor"

PACKAGES = {
    "pdfjs": (
        "https://registry.npmjs.org/pdfjs-dist/-/pdfjs-dist-6.4.299.tgz",
        "sha512-AVl138zALtfaAPvADulE0PZThbYzCBS79nL4pOSL/6Sm/4AH5A21BD9VHt97OlCuzJuCpmeZtAtkinisF4Vb1g==",
        # Only what the probe loads: both builds, the worker inputs and the licenses.
        ("package/LICENSE", "package/build/pdf.mjs", "package/build/pdf.worker.mjs",
         "package/legacy/build/pdf.mjs", "package/legacy/build/pdf.worker.mjs",
         "package/cmaps/", "package/standard_fonts/", "package/iccs/", "package/wasm/"),
    ),
    "pdfium": (
        "https://registry.npmjs.org/@embedpdf/pdfium/-/pdfium-2.15.1.tgz",
        "sha512-qDH4gEkGSQT0iM+07Hpm3LyvoB7PEFbW3Qyk6+LPkEYLJYfHagkHM1OzYlDq2EDOhJRguykTkzdMOT3/w27x5Q==",
        ("package/LICENSE", "package/LICENSE.pdfium", "package/dist/index.browser.js",
         "package/dist/pdfium.wasm"),
    ),
}

PDFJS_COMMIT = "ea6cf5c985fbc77c48fffb30adf4167a0de8d005"
CORPUS_URL = f"https://raw.githubusercontent.com/mozilla/pdf.js/{PDFJS_COMMIT}/test/pdfs/"
# sha256 of each file at PDFJS_COMMIT.
CORPUS = {
    "tracemonkey.pdf": "3662ff519e485810520552bf301d8c3b2b917fd2f83303f4965d7abed367e113",
    "TAMReview.pdf": "a6db6d2f5e4f97e1e28a5919a875a40b89e38d0229b33cc023ca4d353f3ab3ec",
    "ArabicCIDTrueType.pdf": "1cad1de912ba29f89a6d8b08bc5b0f84382874ffedab2c8f9e05ef608c265bb1",
    "jp2k-resetprob.pdf": "582da92f1cad4fa47639e5df5c29ebba6e4ae432ba5e9283297ee6e5e88d7efa",
    "jpx_smaskindata.pdf": "a82813559c74f074b0caf92c80875fc018acb737269a2af5508c7e083b2a5fd3",
    "bitmap-symbol.pdf": "39bf73b128947979f9a6080791ad03e206ea5756f4b4d37b6609e154d4f875b2",
    "function_based_shading.pdf": "21a0d4951f2d45f86d90dd9bac824805b54b2bd2fefe6efad763d227d484ed64",
    "axial_shading_many_inputs.pdf": "4d86a414969c1c849e6866193c2c41653463d694a02ffc91b0ba663a7d107ee8",
    "issue19971.pdf": "178c6207203151cf4036cd377d233e92a3cfb07e7787dd68bc2b2203d5ef3ce1",
}


def fetch(url, cache):
    name = url.rsplit("/", 1)[1]
    if cache and (cache / name).is_file():
        return (cache / name).read_bytes()
    with urllib.request.urlopen(url, timeout=120) as response:
        return response.read()


def check_sri(data, integrity):
    algorithm, expected = integrity.split("-", 1)
    actual = base64.b64encode(hashlib.new(algorithm, data).digest()).decode()
    if actual != expected:
        sys.exit(f"integrity mismatch: expected {integrity}, got {algorithm}-{actual}")


def unpack(data, target, wanted):
    shutil.rmtree(target, ignore_errors=True)
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as archive:
        members = [m for m in archive.getmembers()
                   if m.isfile() and any(m.name == w or (w.endswith("/") and m.name.startswith(w)) for w in wanted)]
        # The "data" filter refuses absolute paths, links and anything leaving the target.
        archive.extractall(target, members=members, filter="data")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--cache", type=pathlib.Path)
    args = parser.parse_args()

    for name, (url, integrity, wanted) in PACKAGES.items():
        data = fetch(url, args.cache)
        check_sri(data, integrity)
        unpack(data, VENDOR / name, wanted)
        print(f"{name}: {url.rsplit('/', 1)[1]} ok")

    corpus = VENDOR / "corpus"
    corpus.mkdir(parents=True, exist_ok=True)
    for name, sha256 in CORPUS.items():
        data = fetch(CORPUS_URL + name, args.cache)
        if hashlib.sha256(data).hexdigest() != sha256:
            sys.exit(f"sha256 mismatch for {name}")
        (corpus / name).write_bytes(data)
        print(f"corpus: {name} ok")


if __name__ == "__main__":
    main()
