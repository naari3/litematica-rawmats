# EMI の BoM (recipe tree) 実装調査

調査日: 2026-07-05
対象: https://github.com/emilyploszaj/emi (ghq クローン: `~/src/github.com/emilyploszaj/emi`)
目的: rawmats に「レシピ家系図」的なグラフ表示を入れる際の参考。

## 観察 (事実)

### データモデル: `xplat/src/main/java/dev/emi/emi/bom/`

- **純粋な木であり DAG ではない**。`MaterialTree` は単一の `goal` (1レシピの出力) を根に持ち、
  `MaterialNode` = ingredient + 選択レシピ + children (レシピの入力)。
  共有素材 (stick 等) はマージせず、出現ごとに別ノード。
  合算は画面下部の「Total cost」行 (`TreeCost` が Map に平坦集計) でのみ行う。
- レシピ選択は 1 ingredient に 1 レシピ (`BoM.getRecipe`)。デフォルト (データ駆動 `RecipeDefaults`)
  + ユーザーの resolution (`addedRecipes`、JSON 永続化 `BoM.saveAdded/loadAdded`)。
- タグ ingredient は `EmiResolutionRecipe` (タグ→具体スタックの合成レシピ) として木に挟み、
  描画時は行を増やさず小さいマーカーで示す (`BoMScreen.Node.resolution`)。
- 循環対策: `MaterialNode.recalculate` が DFS 経路上の使用済みレシピ list を持ち回り、
  経路上に同じレシピが出たら展開停止 (= 経路単位のループ検出。rawmats の
  MaterialListJsonBase の checkIfLoop と同じ発想)。
- rawmats に無い概念: 確率出力 (`ChanceState` を親から子へ伝播、期待値表示 "≈")、
  remainder (バケツ等の返却物、「Leftovers」行)、catalyst (消費されない入力)、
  batch サイズ (`batches`、`TreeCost.getIdealBatch` が gcd/lcm で端数の出ない理想バッチを計算)、
  crafting mode (インベントリと突き合わせて進捗を色表示)。

### 表示: `xplat/src/main/java/dev/emi/emi/screen/BoMScreen.java`

- 専用 `Screen` の**無限キャンバス**。ドラッグでパン、スクロールでズーム
  (`zoom`、GUI スケール整数刻み)、ホバーでツールチップ、左クリックでレシピ画面へ、
  右クリックで折り畳み (`FoldState.EXPANDED/COLLAPSED/IGNORED`)、
  Shift+右クリックで resolution 解除。
- **レイアウトは自前実装の整然木 (Reingold–Tilford の簡易版)**。外部ライブラリ不使用。
  - `TreeVolume` = 部分木のノード群 + 深さごとの [left, right] 幅 (輪郭 contour)。
  - 子を再帰レイアウト → `addToRight` が兄弟の輪郭を全深さで比較し、
    重ならない最小シフト幅 (max over depths) で右に詰める。
  - `addHead` で親を子スパンの中央 x に置く。y = depth × 20px 固定。
  - 実装は `TreeVolume` クラス約 80 行 (BoMScreen.java:791-870)。移植は容易そう。
- 辺は**直交線** (縦横の 1px fill 矩形)。親の下 19px に水平バスを引き、各子へ垂直線を落とす。
  線色が状態を兼ねる: 白=通常、金=確率、緑=完了、紫=部分完了 (crafting mode)、青=ホバー。
- ノード = アイテムアイコン 16px + 個数テキスト。レシピ持ちノードは枠 +
  レシピカテゴリアイコン (作業台/かまど等) を左に併記。
- 木の下に「Total cost」(平坦化した原材料合計) と「Leftovers」(余り) の行を表示。

## 仮説・所感

- EMI は「共有素材のマージ (DAG 化) をせず、木 + 平坦合計の二段構え」で
  レイアウト問題を回避していると思われる。これは rawmats の現構造
  (CraftNode 展開木 + MatRow フラットリスト) と同型なので、
  ゲーム内ツリー表示をやるなら TreeVolume 方式の移植が最短経路になりそう。
- DAG 表示 (現在の DOT 書き出し) と EMI 型ツリー表示は補完関係:
  前者は共有素材の集中が見え、後者は「何を何回クラフトするか」の手順が読みやすい。

## 関連

- rawmats 側の DOT 書き出し: `CraftDotExport` (2026-07-05 実装)。
  アイテム種別マージの DAG。Graphviz `dot -Tsvg` でレンダリング確認済み。
