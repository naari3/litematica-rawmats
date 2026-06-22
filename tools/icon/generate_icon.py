#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.9"
# dependencies = ["pillow", "numpy"]
# ///
"""Generate the RawMaterials icon from the Litematica base icon.

Pipeline:
  1. Load the 32x32 Litematica icon (litematica_base.png).
  2. Shift the central emblem up a few px and clear the freed bottom rows.
  3. Upscale 32 -> 64 with nearest neighbour (each source pixel becomes 2x2).
  4. Draw "RAWMATS" with a 5x7 pixel font in the freed space (64px grid).
  5. Add a pixel "gloss":
       - emblem: rim light computed on the 32px grid so highlights snap to the
         emblem's real pixels, then upscaled to 64px.
       - text: a diagonal drop shadow on the 64px grid (white glyphs stay flat;
         a rim light does not read well on pure white).

Outputs (written to the repo root):
  - icon.png       (64x64,  mod / in-game icon)
  - icon-512.png   (512x512, store upload)

To use it as the mod icon, copy icon.png to
src/main/resources/assets/rawmats/icon.png.

Run from anywhere:  uv run tools/icon/generate_icon.py
(uv reads the inline script metadata above and installs Pillow + numpy.)
"""
import os

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.normpath(os.path.join(HERE, "..", ".."))
SRC = os.path.join(HERE, "litematica_base.png")
OUT_DIR = REPO
OUT_ICON = os.path.join(OUT_DIR, "icon.png")
OUT_STORE = os.path.join(OUT_DIR, "icon-512.png")

# ---- tunables ----
BG = (34, 32, 52)            # Litematica background navy
WHITE = (255, 255, 255)
SHIFT = 3                    # px to move the emblem up (32px space)
TEXT = "RAWMATS"
TEXT_Y = 48                  # top row of the text (64px space)
CHAR_W, CHAR_GAP = 5, 1      # glyph cell width / spacing (64px space)
EXTRA_BEFORE = {3: 1}        # +1px gap before index 3 -> splits "RAW" / "MATS"
GLOSS = 0.60                 # overall emblem gloss strength (0..1; 1.0 = full)
RIM = 0.9                    # emblem rim-light highlight strength (at GLOSS=1)
RIM_SHADE = 0.6             # emblem rim shadow strength (at GLOSS=1)
SHEEN = 0.18                # faint diagonal sheen over the emblem (at GLOSS=1)
SHADOW_OFFSET = (1, 1)      # text drop shadow (dy, dx) on the 64px grid
SHADOW_DARKEN = 0.45        # shadow colour = BG * this

# 5x7 pixel font, only the letters used by "RAWMATS".
FONT = {
    "R": ["11110", "10001", "10001", "11110", "10100", "10010", "10001"],
    "A": ["01110", "10001", "10001", "11111", "10001", "10001", "10001"],
    "W": ["10001", "10001", "10001", "10101", "10101", "10101", "01010"],
    "M": ["10001", "11011", "10101", "10101", "10001", "10001", "10001"],
    "T": ["11111", "00100", "00100", "00100", "00100", "00100", "00100"],
    "S": ["01111", "10000", "10000", "01110", "00001", "00001", "11110"],
}


def build_base():
    """Steps 1-4: emblem shift + upscale + text -> 64x64 RGB image."""
    im = Image.open(SRC).convert("RGBA")
    bg = BG + (255,)
    white = WHITE + (255,)

    # shift the emblem up; the source emblem lives in rows 7..24, cols 1..30
    art = im.crop((1, 7, 31, 25))
    base = im.copy()
    base.paste(art, (1, 7 - SHIFT))
    px = base.load()
    for y in range(22, 31):          # clear the freed bottom rows
        for x in range(1, 31):
            px[x, y] = bg

    big = base.resize((64, 64), Image.NEAREST)
    bpx = big.load()

    total = 0
    for i in range(len(TEXT)):
        if i > 0:
            total += CHAR_GAP
        total += EXTRA_BEFORE.get(i, 0) + CHAR_W
    x = 1 + (62 - total) // 2        # centre within the 64px frame
    for i, ch in enumerate(TEXT):
        if i > 0:
            x += CHAR_GAP
        x += EXTRA_BEFORE.get(i, 0)
        for ry, row in enumerate(FONT[ch]):
            for rx, c in enumerate(row):
                if c == "1":
                    bpx[x + rx, TEXT_Y + ry] = white
        x += CHAR_W
    return big.convert("RGB")


def find_runs(nonbg, inner):
    rows = (nonbg * inner).sum(axis=1)
    runs, y, N = [], 0, nonbg.shape[0]
    while y < N:
        if rows[y] > 0:
            s = y
            while y < N and rows[y] > 0:
                y += 1
            runs.append((s, y - 1))
        else:
            y += 1
    return runs


def screen(a, add):
    return 1.0 - (1.0 - a) * (1.0 - add[..., None])


def mul(a, dark):
    return a * (1.0 - dark[..., None])


def main():
    img = build_base()
    N = img.size[0]                  # 64
    base = np.asarray(img).astype(np.float32) / 255.0

    ys64, xs64 = np.mgrid[0:N, 0:N]
    bg = np.array(BG, dtype=np.float32) / 255.0
    dist = np.sqrt(((base - bg) ** 2).sum(axis=2))
    nonbg = (dist > 0.06).astype(np.float32)
    inner = (xs64 >= 2) & (xs64 <= N - 3) & (ys64 >= 2) & (ys64 <= N - 3)

    runs = find_runs(nonbg, inner)   # [emblem run, text run]
    logo_run = max(runs, key=lambda r: r[1] - r[0])
    text_run = [r for r in runs if r != logo_run][0]

    logo_mask = nonbg * inner.astype(np.float32)
    logo_mask[: logo_run[0], :] = 0.0
    logo_mask[logo_run[1] + 1 :, :] = 0.0
    text_mask = nonbg * inner.astype(np.float32)
    text_mask[: text_run[0], :] = 0.0
    text_mask[text_run[1] + 1 :, :] = 0.0

    # ---- emblem: rim light on the 32px grid, then upscale ----
    small = base[0::2, 0::2, :]
    M = N // 2
    ys, xs = np.mgrid[0:M, 0:M]
    u = (xs.astype(np.float32) + ys.astype(np.float32)) / (2.0 * (M - 1))
    lum = small.mean(axis=2)
    upleft = np.zeros_like(lum)
    upleft[1:, 1:] = lum[:-1, :-1]
    edge = lum - upleft
    c = screen(small, np.clip(edge, 0, 1) * RIM * GLOSS)
    c = mul(c, np.clip(-edge, 0, 1) * RIM_SHADE * GLOSS)
    c = screen(c, np.exp(-((u - 0.30) / 0.14) ** 2) * SHEEN * GLOSS)
    g_logo = np.repeat(np.repeat(c, 2, axis=0), 2, axis=1)

    out = base + (g_logo - base) * logo_mask[..., None]

    # ---- text: diagonal drop shadow on the 64px grid ----
    tm = text_mask > 0.5
    dy, dx = SHADOW_OFFSET
    sh = np.zeros_like(tm)
    sh[dy:, dx:] = tm[: N - dy, : N - dx]
    shadow_only = sh & (~tm)
    out[shadow_only] = bg * SHADOW_DARKEN

    out = np.clip(out, 0, 1)
    os.makedirs(OUT_DIR, exist_ok=True)
    icon = Image.fromarray((out * 255).round().astype(np.uint8), "RGB")
    icon.save(OUT_ICON)
    icon.resize((512, 512), Image.NEAREST).save(OUT_STORE)
    print("wrote", OUT_ICON)
    print("wrote", OUT_STORE)
    print("emblem rows", logo_run, "text rows", text_run)


if __name__ == "__main__":
    main()
