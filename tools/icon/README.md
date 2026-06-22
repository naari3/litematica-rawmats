# Icon generator

Regenerates the RawMaterials icon from the Litematica base icon.

## Run

```sh
uv run tools/icon/generate_icon.py
```

The script carries its dependencies (Pillow, numpy) as inline
[PEP 723](https://peps.python.org/pep-0723/) metadata, so `uv` installs them
automatically. No virtualenv or `pip install` needed.

Outputs go to the repo root:

- `icon.png` — 64x64, the mod / in-game icon
- `icon-512.png` — 512x512, for the Modrinth / CurseForge project page

To use the result as the mod icon, copy `icon.png` to
`src/main/resources/assets/rawmats/icon.png`.

## What it does

1. Loads `litematica_base.png` (32x32).
2. Shifts the central emblem up a few pixels and clears the freed bottom rows.
3. Upscales 32 -> 64 with nearest neighbour (each source pixel becomes 2x2).
4. Draws `RAWMATS` with a built-in 5x7 pixel font in the freed space.
5. Adds a pixel gloss:
   - emblem: a rim light computed on the original 32px grid, so the highlights
     line up with the emblem's real pixels, then upscaled to 64px.
   - text: a diagonal drop shadow on the 64px grid. The white glyphs are left
     flat because a rim light does not read on pure white.

Tweak the constants near the top of `generate_icon.py` (shift amount, text
position, gloss strengths, shadow offset) to adjust the look.

## Attribution

`litematica_base.png` is the icon of
[Litematica](https://github.com/sakura-ryoko/litematica) (LGPL-3.0), used here
as the base for this addon's derivative icon. RawMaterials is also LGPL-3.0.
