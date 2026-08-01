#!/usr/bin/env python3
"""Generate Shippy's legacy and adaptive Android launcher assets."""

from __future__ import annotations

from pathlib import Path

from PIL import Image


REPO = Path(__file__).resolve().parents[2]
SOURCE = REPO / "assets" / "branding" / "shippy_icon.ico"
PREVIEW = REPO / "assets" / "branding" / "shippy_icon_256.png"
RES = REPO / "app" / "src" / "main" / "res"
LEGACY_SIZES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}


def resized(image: Image.Image, size: int) -> Image.Image:
    return image.resize((size, size), Image.Resampling.LANCZOS)


def main() -> None:
    with Image.open(SOURCE) as source:
        icon = source.convert("RGBA")

    icon.save(PREVIEW, optimize=True)
    for directory, size in LEGACY_SIZES.items():
        destination = RES / directory / "ic_launcher.webp"
        resized(icon, size).save(destination, "WEBP", lossless=True, method=6)

    # Adaptive icons use a 108 dp canvas. Keep all supplied artwork inside the 66 dp safe zone
    # while retaining the original transparent pixels and exact artwork proportions.
    adaptive_size = 432
    source_size = 310
    foreground = Image.new("RGBA", (adaptive_size, adaptive_size), (0, 0, 0, 0))
    scaled = resized(icon, source_size)
    offset = (adaptive_size - source_size) // 2
    foreground.alpha_composite(scaled, (offset, offset))

    drawable = RES / "drawable-xxxhdpi"
    drawable.mkdir(parents=True, exist_ok=True)
    foreground.save(drawable / "ic_launcher_foreground_image.png", optimize=True)

    monochrome = Image.new("RGBA", foreground.size, (255, 255, 255, 0))
    monochrome.putalpha(foreground.getchannel("A"))
    monochrome.save(drawable / "ic_launcher_monochrome_image.png", optimize=True)

    print("Generated Shippy launcher assets from", SOURCE)


if __name__ == "__main__":
    main()
