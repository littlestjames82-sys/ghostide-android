#!/usr/bin/env python3
"""Launcher icons for GhostIDE: dark rounded square, green ghost glyph.

Simple by design (matches the other Ghost apps' flat icons). Writes
ic_launcher.png and ic_launcher_round.png into each mipmap-*dpi dir.
"""
import pathlib
from PIL import Image, ImageDraw

RES = pathlib.Path(__file__).parent / "app" / "src" / "main" / "res"
SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
SS = 4  # supersample

DARK = (23, 24, 28, 255)      # #17181C
GREEN = (44, 110, 73, 255)    # #2C6E49
LIGHT = (123, 216, 143, 255)  # #7BD88F


def draw_icon(size, round_icon):
    s = size * SS
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    if round_icon:
        d.ellipse([0, 0, s - 1, s - 1], fill=DARK)
    else:
        r = int(s * 0.22)
        d.rounded_rectangle([0, 0, s - 1, s - 1], radius=r, fill=DARK)
    # violet ring/edge accent
    if round_icon:
        d.ellipse([s * 0.03, s * 0.03, s * 0.97, s * 0.97], outline=GREEN, width=int(s * 0.03))
    # ghost body: rounded top, flat-ish wavy bottom
    gx0, gy0, gx1, gy1 = s * 0.28, s * 0.22, s * 0.72, s * 0.78
    d.pieslice([gx0, gy0, gx1, gy0 + (gx1 - gx0)], start=180, end=0, fill=LIGHT)
    d.rectangle([gx0, gy0 + (gx1 - gx0) / 2, gx1, gy1], fill=LIGHT)
    # wavy bottom: three background-colored circles read as cuts,
    # because the icon background is uniformly DARK
    wave_r = (gx1 - gx0) / 6.0
    for i in range(3):
        cx = gx0 + wave_r * (1 + 2 * i)
        d.ellipse([cx - wave_r, gy1 - wave_r, cx + wave_r, gy1 + wave_r], fill=DARK)
    # eyes
    er = s * 0.045
    for ex in (s * 0.42, s * 0.58):
        ey = s * 0.44
        d.ellipse([ex - er, ey - er * 1.3, ex + er, ey + er * 1.3], fill=DARK)
    img = img.resize((size, size), Image.LANCZOS)
    return img


for dpi, size in SIZES.items():
    out = RES / f"mipmap-{dpi}"
    out.mkdir(parents=True, exist_ok=True)
    draw_icon(size, False).save(out / "ic_launcher.png")
    draw_icon(size, True).save(out / "ic_launcher_round.png")
    print(dpi, size, "ok")
