#!/usr/bin/env python3
"""Convert pinned official Lucide SVGs to dependency-free Android vectors."""

from __future__ import annotations

import argparse
import json
import math
import xml.etree.ElementTree as ET
from pathlib import Path


ANDROID_NS = "http://schemas.android.com/apk/res/android"
ANDROID = f"{{{ANDROID_NS}}}"
SVG_NS = "http://www.w3.org/2000/svg"
SVG = f"{{{SVG_NS}}}"


def number(value: float) -> str:
    if math.isclose(value, round(value)):
        return str(round(value))
    return f"{value:g}"


def svg_path(element: ET.Element) -> str:
    kind = element.tag.removeprefix(SVG)
    if kind == "path":
        return element.attrib["d"]
    if kind == "line":
        return "M {x1} {y1} L {x2} {y2}".format(**element.attrib)
    if kind in {"polyline", "polygon"}:
        points = element.attrib["points"].replace(",", " ").split()
        pairs = list(zip(points[0::2], points[1::2], strict=True))
        path = "M " + " L ".join(f"{x} {y}" for x, y in pairs)
        return path + (" Z" if kind == "polygon" else "")
    if kind == "circle":
        cx = float(element.attrib["cx"])
        cy = float(element.attrib["cy"])
        radius = float(element.attrib["r"])
        return (
            f"M {number(cx - radius)} {number(cy)} "
            f"A {number(radius)} {number(radius)} 0 1 0 {number(cx + radius)} {number(cy)} "
            f"A {number(radius)} {number(radius)} 0 1 0 {number(cx - radius)} {number(cy)} Z"
        )
    if kind == "ellipse":
        cx = float(element.attrib["cx"])
        cy = float(element.attrib["cy"])
        rx = float(element.attrib["rx"])
        ry = float(element.attrib["ry"])
        return (
            f"M {number(cx - rx)} {number(cy)} "
            f"A {number(rx)} {number(ry)} 0 1 0 {number(cx + rx)} {number(cy)} "
            f"A {number(rx)} {number(ry)} 0 1 0 {number(cx - rx)} {number(cy)} Z"
        )
    if kind == "rect":
        x = float(element.attrib.get("x", "0"))
        y = float(element.attrib.get("y", "0"))
        width = float(element.attrib["width"])
        height = float(element.attrib["height"])
        radius = float(element.attrib.get("rx", element.attrib.get("ry", "0")))
        if radius == 0:
            return (
                f"M {number(x)} {number(y)} H {number(x + width)} "
                f"V {number(y + height)} H {number(x)} Z"
            )
        radius = min(radius, width / 2, height / 2)
        return (
            f"M {number(x + radius)} {number(y)} H {number(x + width - radius)} "
            f"A {number(radius)} {number(radius)} 0 0 1 {number(x + width)} {number(y + radius)} "
            f"V {number(y + height - radius)} A {number(radius)} {number(radius)} 0 0 1 "
            f"{number(x + width - radius)} {number(y + height)} H {number(x + radius)} "
            f"A {number(radius)} {number(radius)} 0 0 1 {number(x)} {number(y + height - radius)} "
            f"V {number(y + radius)} A {number(radius)} {number(radius)} 0 0 1 "
            f"{number(x + radius)} {number(y)} Z"
        )
    raise ValueError(f"Unsupported SVG element: {kind}")


def drawable_paths(svg_root: ET.Element) -> list[str]:
    result: list[str] = []
    for element in svg_root.iter():
        if element is svg_root:
            continue
        kind = element.tag.removeprefix(SVG)
        if kind == "g":
            if element.attrib.get("transform"):
                raise ValueError("Transformed SVG groups are not supported")
            continue
        result.append(svg_path(element))
    if not result:
        raise ValueError("SVG contains no drawable geometry")
    return result


def render(source: Path, destination: Path, lucide_name: str) -> str:
    svg_file = source / f"{lucide_name}.svg"
    if not svg_file.is_file():
        raise FileNotFoundError(svg_file)

    existing = ET.parse(destination).getroot()
    svg_root = ET.parse(svg_file).getroot()
    view_box = svg_root.attrib.get("viewBox")
    if view_box != "0 0 24 24":
        raise ValueError(f"Unexpected viewBox in {svg_file}: {view_box}")

    preserved = {
        key: value
        for key, value in existing.attrib.items()
        if key in {ANDROID + "width", ANDROID + "height", ANDROID + "tint", ANDROID + "autoMirrored"}
    }
    vector = ET.Element(
        "vector",
        {
            "xmlns:android": ANDROID_NS,
            "android:width": preserved[ANDROID + "width"],
            "android:height": preserved[ANDROID + "height"],
            "android:viewportWidth": "24",
            "android:viewportHeight": "24",
        },
    )
    if ANDROID + "tint" in preserved:
        vector.set("android:tint", preserved[ANDROID + "tint"])
    if ANDROID + "autoMirrored" in preserved:
        vector.set("android:autoMirrored", preserved[ANDROID + "autoMirrored"])

    vector.append(ET.Comment(f" Lucide {lucide_name}; generated from the pinned official SVG. "))
    for path_data in drawable_paths(svg_root):
        ET.SubElement(
            vector,
            "path",
            {
                "android:fillColor": "@android:color/transparent",
                "android:strokeColor": "@android:color/white",
                "android:strokeWidth": "2",
                "android:strokeLineCap": "round",
                "android:strokeLineJoin": "round",
                "android:pathData": path_data,
            },
        )

    ET.indent(vector, space="    ")
    xml = '<?xml version="1.0" encoding="utf-8"?>\n' + ET.tostring(vector, encoding="unicode") + "\n"
    return xml


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("lucide_icons", type=Path, help="Path to the official Lucide icons directory")
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--check", action="store_true", help="Verify generated resources without writing")
    args = parser.parse_args()

    config_path = Path(__file__).with_name("lucide-icons.json")
    config = json.loads(config_path.read_text(encoding="utf-8"))
    drawable = args.repo / "app" / "src" / "main" / "res" / "drawable"
    for resource_name, lucide_name in config["icons"].items():
        destination = drawable / f"{resource_name}.xml"
        if not destination.is_file():
            raise FileNotFoundError(destination)
        xml = render(args.lucide_icons, destination, lucide_name)
        if args.check:
            if destination.read_text(encoding="utf-8") != xml:
                raise SystemExit(f"Generated resource is stale: {destination}")
        else:
            destination.write_text(xml, encoding="utf-8", newline="\n")
    action = "Validated" if args.check else "Generated"
    print(f"{action} {len(config['icons'])} Android vectors from official Lucide SVGs")


if __name__ == "__main__":
    main()
