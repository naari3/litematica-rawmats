# Litematica RawMaterials

A [Litematica](https://github.com/sakura-ryoko/litematica) addon that expands the material list into raw materials.

<img width="2560" height="1440" alt="2026-06-22_20 26 30" src="https://github.com/user-attachments/assets/e70d3894-bde5-480f-9f6b-fe93f0c9f94a" />

Litematica's material list shows what a schematic is made of, but anything you'd normally craft (a block of redstone, a hopper, a chest) is listed as-is. RawMaterials follows the crafting recipes and shows the total raw materials you need to gather, minus what's already in your inventory.

## Requirements

- Minecraft 26.2 (Fabric)
- [malilib](https://github.com/sakura-ryoko/malilib) 0.29.0+
- [Litematica](https://github.com/sakura-ryoko/litematica) 0.28.0+

## Features

### Recipe expansion

Crafted items are broken down into their ingredients, recursively, down to raw materials.

### Inventory netting

Items already in your inventory are subtracted, so the list shows what's actually left to gather. Fully covered branches stop expanding and turn green.

### Expand and fold

Open or collapse individual rows, or the whole list at once.

### Tag ingredients

When a recipe accepts a tag (any planks, any log), you choose which item to count toward it.

## Usage

Open a material list in Litematica first; RawMaterials uses the most recently opened one. Then press `M + K` to open it as a raw-material list.

Inside the list:

- **Left-click** a row to expand it into its ingredients.
- **Right-click** a row to fold it back into the crafted item.
- **Middle-click** a tag row to pick which item to use.

Press `M + J` to open the settings screen, where you can rebind both keys.

## Notes

Recipes come from your client's recipe book, the same source Litematica uses. On a vanilla multiplayer server, recipes you haven't unlocked won't expand, so some totals may not be fully broken down until you've unlocked them.

## Building

```sh
./gradlew build
```

The mod jar is written to `build/libs/` (e.g. `rawmats-fabric-26.2-0.1.0.jar`). Put it in your `mods` folder along with malilib and Litematica for the same Minecraft version.
