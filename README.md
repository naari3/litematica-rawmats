# Litematica RawMaterials

A [Litematica](https://github.com/sakura-ryoko/litematica) addon that expands the material list into raw materials.

<img width="2560" height="1440" alt="2026-06-22_20 26 30" src="https://github.com/user-attachments/assets/e70d3894-bde5-480f-9f6b-fe93f0c9f94a" />

Litematica's material list shows what a schematic is made of, but anything you'd normally craft (a block of redstone, a hopper, a chest) is listed as-is. RawMaterials follows the crafting recipes and shows the total raw materials you need to gather, minus what's already in your inventory.

## Requirements

- Minecraft 26.2 (Fabric)
- [malilib](https://github.com/sakura-ryoko/malilib) 0.29.0+
- [Litematica](https://github.com/sakura-ryoko/litematica) 0.28.0+

## Usage

Press `M + K` to open the raw-material list. It uses the most recently opened Litematica material list; if none is open yet, it is generated automatically from the currently selected schematic placement (same as Litematica's `M + L`). The hotkey also works while a container screen is open (inventory, crafting table, chest), and `Esc` brings you back to it.

Press `M + J` to open the settings screen, where you can rebind both keys.

## Views

The list can be viewed in three ways. They all share the same state — expanded items, ignored items, tag choices, and the multiplier — and all compute their numbers the same way, so switching views never changes the totals.

### Raw Material List

The main flat list, styled after Litematica's material list (Item / Total / Missing / Available columns, sortable).

- **Left-click** a row to expand it into its ingredients.
- **Right-click** a row to fold it back into the crafted item.
- **Shift + Right-click** a row to ignore it (removes it and everything it would be crafted into).
- **Middle-click** a tag row to pick which item to use.
- **Hold Tab** while hovering a row to see its crafting recipe.

Extras: a multiplier for building the schematic N times, a "Hide available" toggle, plain-text export, and an "Ignored: N" button that opens a menu where ignored items can be un-ignored one by one.

### Crafting Flow

Opened with the **Flow view** button. Item types are merged into a single left-to-right graph: raw materials on the left flow through intermediate crafts into the schematic's materials on the right. Shared ingredients (like sticks) appear once, with edges fanning out to every item that uses them.

![Crafting Flow](docs/images/crafting-flow.png)

- Same interactions as the list: left-click expand, right-click fold (a raw material folds into its consumer), Shift+right-click ignore, middle-click tag choice, Tab recipe.
- Counts are inventory-netted; fully covered items get a green checkmark, partial stock shows `have/total`.
- Hovering a node highlights the paths through it — the materials it needs and everything it flows into.
- Drag to pan, scroll to zoom, plus Expand all / Collapse all / Hide available buttons.

### Recipe Tree

Opened with the **Tree view** button. A per-recipe tree where shared ingredients are shown once per use, so each item's full recipe structure can be read on its own. Drag to pan, scroll to zoom, click a node to fold or unfold its subtree.

## Features

### Recipe expansion

Crafted items are broken down into their ingredients, recursively, down to raw materials.

### Inventory netting

Items already in your inventory are subtracted, so the list shows what's actually left to gather. Fully covered branches stop expanding and turn green. Intermediate items you already have (say, pistons) stop requesting their own materials, in every view.

### Tag ingredients

When a recipe accepts a tag (any planks, any log), you choose which item to count toward it.

### Ignoring items

Anything you don't want counted (items you farm anyway, or a branch you'll source differently) can be ignored with Shift+right-click and brought back later from the ignored menu.

## Notes

Recipes come from your client's recipe book, the same source Litematica uses. On a vanilla multiplayer server, recipes you haven't unlocked won't expand, so some totals may not be fully broken down until you've unlocked them.

## Building

```sh
./gradlew build
```

The mod jar is written to `build/libs/` (e.g. `rawmats-fabric-26.2-0.1.0.jar`). Put it in your `mods` folder along with malilib and Litematica for the same Minecraft version.
