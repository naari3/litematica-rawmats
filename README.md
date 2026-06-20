# RawMaterials (Litematica addon)

Litematica の material list を、**クラフト前提の資材を再帰展開した原材料の合計**で表示するクライアント側アドオン。
(例: schematic が redstone block を要求 → 必要な redstone dust の総数として集計)

- Minecraft **26.2** / Fabric
- 依存: [malilib] 0.29.0+, [litematica] 0.28.0+ (sakura-ryoko fork)

> ステータス: **scaffold / WIP**。基本フローのみ。詳細・既知の課題は [DESIGN.md](./DESIGN.md)。

## 使い方 (v0)

1. Litematica で通常通り material list を開く (一度開けば直近のリストとして記憶される)。
2. hotkey **`M, R`** を押すと、その material list を原材料展開したビューが開く。

内部では Litematica 内蔵の再帰レシピ分解 (`materials/json`) を再利用している。
レシピ取得は ClientRecipeBook 依存のため、**バニラ SMP では recipe book で未アンロックの
レシピは展開されない**可能性がある (DESIGN.md 参照)。

## ビルド

```sh
./gradlew build
```

成果物: `build/libs/rawmats-fabric-26.2-<version>.jar`。
malilib と litematica と同じ MC バージョン (26.2) のものを mods に入れること。

## 開発メモ

- マッピングは Mojang official (mojmap)。
- 依存解決は `https://masa.dy.fi/maven/sakura-ryoko`。
- `runClient` で malilib/litematica が読まれない場合は build.gradle のコメント参照。

[malilib]: https://github.com/sakura-ryoko/malilib
[litematica]: https://github.com/sakura-ryoko/litematica
