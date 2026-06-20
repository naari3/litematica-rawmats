# litematica-rawmats 設計・調査ノート

Litematica の material list を拡張し、クラフト前提の資材 (例: redstone block) を
再帰展開して「全体のクラフトに必要な原材料の合計」を表示するアドオン。

- 対象: Minecraft **26.2** / Fabric
- 依存: malilib 0.29.0 / litematica 0.28.0 (いずれも sakura-ryoko fork, `masa.dy.fi/maven/sakura-ryoko`)
- マッピング: Mojang official (mojmap)

このノートは時系列追記式。結論が覆った場合も古い項目は消さず「訂正」を追記する。
観察 (事実) と仮説 (推論) は分けて書く。

---

## 現状サマリ (2026-06-20 時点)

v0.5 まで実装・ビルド通過・runClient 起動確認済み。実装済み:

- フラット買い物リスト (普通の material list 風) + 左クリック展開 / 右クリック fold (in-place 置換)。
- 在庫ネット: 完全充足した枝は展開停止 (緑表示)。
- 単一候補は即実行、複数候補のときだけアイコン式ポップアップで親を選択 (系譜分離)。
- ホットキー `M,K` で現在の Litematica material list から起動。

データ源は方針 A (Litematica 内蔵の `MaterialListJsonBase` 再帰展開、ClientRecipeBook 依存)。

## Phase 2 バックログ (集約)

優先度順は未確定。各項目の背景は下の時系列ログ参照。

1. **部分在庫スケーリング**: 半端に在庫がある item を展開したとき、子を「不足分」で按分する。
   現状は完全充足のみ展開停止、部分展開は baked の gross 個数のまま。在庫引き当ても DFS 先着の素朴版。
2. **タグ材料の置換 (⑤)**: stick=oak/birch 等、タグ材料 (any planks 等) でどの具体素材を使うか選択。
   現状は Litematica の `overridePrimaryMaterial` 由来の自動選択。per-item の override を持たせる想定。
3. **fold 粒度**: 現状は親「種別」単位で畳む。子個別 (per-edge) の fold 等のより細かい操作。
4. **UX 各種**: multiplier (Litematica 同様の倍率)、検索バー、hotkey リバインド GUI
   (`Registry.CONFIG_SCREEN` 登録 + config 画面)。
5. **性能**: 大規模 schematic でツリーを eager 全構築しているコストの遅延化。
6. **SMP 完全性 (方針 B)**: ClientRecipeBook 依存のため未アンロックレシピは展開されない。
   完全性が必要なら、バニラ 26.2 のレシピから生成した同梱展開テーブルをフォールバックに追加する。

---

## 2026-06-20 初期調査

### 事実: バージョン体系と参照リポジトリ

- masa 本家 (`maruohon/litematica`, `maruohon/malilib`) は全ブランチ `pre-rewrite/*`、
  Fabric の最新が `pre-rewrite/fabric/1.21.1-masa` 止まり。デフォルトは `ornithe/1.12.2`。
  → masa はリライト進行中で、現行 MC 版は本家には無い。
- 現行版は **sakura-ryoko fork** が維持。ブランチは `26.2` (Minecraft は年ベース版数に移行)。
  - `fi.dy.masa.litematica:litematica-fabric-26.2:0.28.0`
  - `fi.dy.masa.malilib:malilib-fabric-26.2:0.29.0`
  - 共に `https://masa.dy.fi/maven/sakura-ryoko` に publish 済み (2026-06-17 時点)。
- ビルド: loom `1.17.+`, Java **25** (`options.release = 25`), UTF-8。
  litematica の build.gradle には **mappings 行が無い** (loom 1.17 が mojmap を default 供給する模様 = 仮説)。

### 事実: Litematica は既に再帰レシピ分解を内蔵している (重要)

当初「RecipeManager で再帰全展開を自前実装」を想定していたが、**Litematica 26.2 は
既にこの分解ロジックを持っていた**。ただし **JSON ファイル出力にしか露出していない**。

- `GuiMaterialList` の **WRITE_TO_JSON ボタン** (`GuiMaterialList.java:328`) が
  `raw_material_list_recipe_details` / `_recipe_steps` / `_simplified` を出力。
  - Shift = missing のみ / Alt = craftingOnly (stonecutter より crafting を優先)。
  - 詳細ツリー (`recipe_details`) は config `Configs.Generic.MATERIAL_LIST_RECIPE_DETAILS` が ON のとき。
- 中核は `fi.dy.masa.litematica.materials.json` パッケージ:
  - `MaterialListJsonBase` — 1 アイテムを stonecutter → crafting → furnace の順でレシピ照合し再帰展開。
    `checkIfLoop()` (`:139`) で「dust → block → dust」等の **循環を検出**。
  - `MaterialListJsonCache` — 再帰 step を重複排除して合算 (`putCombinedEntry` `:93`)。
    `combineUnpackedItems` (`:125`) で **nugget→ingot→block の再パック** (9 個/4 個構成) まで実施。
    `getEntriesCombined()` が「合算後の原材料リスト」。
  - これらは全て public。アドオンから直接呼べる (= 方針 A の根拠)。

### 事実: レシピデータ源は ClientRecipeBook (SMP での制約に直結)

- 分解は malilib の `RecipeBookUtils.getDisplayEntryFromRecipeBook()` (`RecipeBookUtils.java:148`) 経由で、
  `mc.player.getRecipeBook()` = **ClientRecipeBook** を読む。
- 仮説 (web 調査の示唆、要 in-game 検証): Minecraft 1.21.2 のレシピ改修以降、
  サーバはクライアントへ「アンロック済み・非 special のレシピの RecipeDisplay のみ」を送る。
  → **バニラ SMP では、プレイヤーが recipe book で未アンロックのレシピは展開されない**可能性が高い。
- シングルプレイは統合サーバの full RecipeManager があるが、上記実装は SP でも ClientRecipeBook を見るため、
  同様にアンロック状況に依存する可能性がある (要検証)。

### 事実: material list GUI の拡張性

- malilib に material list 描画への注入口 (公式アドオン API) は無い。
  拡張は mixin か並行 GUI の二択。
- データは全工程が `List<MaterialListEntry>` で流れる (`MaterialListBase.getMaterialsAll()`)。
  `MaterialListBase` は public abstract、`GuiMaterialList(MaterialListBase)` も public。
  → **`MaterialListBase` を継承して展開後エントリを流し込めば、GuiMaterialList・ソート・HUD・
    JSON 出力を mixin 無しで丸ごと再利用できる**。これを v0 の方式に採用。
- `WidgetMaterialListEntry.getColumnPosX` (`:122`) には既に 5 列目スロット (`case 4`) が空いており、
  将来「元 GUI に列を 1 本足す」mixin 方式の余地もある。
- 先行事例 `cubicmetre/stormatica-extension` は「raw material list 拡張」を謳うが
  **addon ではなく fork** (Litematica を完全置換, 対象 1.21.10)。
  作者が fork を選んだこと自体が「material list GUI はクリーンに拡張しづらい」傍証。

---

## 決定事項 (2026-06-20)

- **レシピ源 = 方針 A** (Litematica 内蔵分解を再利用)。ユーザー判断「一旦 A、使われ方次第」。
  - 利点: 実装最小、furnace/stonecutter/循環検出/再パックまで込み。
  - 欠点: ClientRecipeBook 依存のため、未アンロックレシピは展開されない (SMP)。
  - 将来: 完全性が必要になったら方針 B (バニラレシピ同梱テーブル) をフォールバックに追加。
- **UI = v0 は mixin 無し**。`RawMaterialList extends MaterialListBase` を `GuiMaterialList` に渡して
  既存 GUI を流用。v1 で元 GUI へのトグルボタン/列追加 (mixin) を検討。

## v0 アーキテクチャ

```
hotkey (M,R)
  -> KeyCallbacks.onKeyAction
       source = DataManager.getMaterialList()   // 直近に開いた material list
       raw    = new RawMaterialList(source)      // ↓で展開・合算
       GuiBase.openGui(new GuiMaterialList(raw)) // 既存 GUI を流用
RawMaterialList.reCreateMaterialList()
  -> MaterialListJson.readMaterialListAll(source, cache, craftingOnly)
  -> cache.getEntriesCombined()                  // 合算後の原材料
  -> 各 Entry を MaterialListEntry に変換 (available は在庫から)
```

## 既知の課題 / TODO

1. **展開粒度 (packed vs raw)**: `getEntriesCombined()` は repack 後 = ingot/nugget を block+端数に
   まとめた形。「redstone dust の総数」のような最小単位表示が欲しい場合は repack を通さない経路が必要
   (`getEntriesFlat` ベースで自前合算、または Cache に repack 無し getter を追加)。
   → ユーザーの当初例 (redstone block → dust 総数) は、用途次第でこちらが本命の可能性。
2. **SMP でのアンロック依存**: 上記「事実」の通り。in-game で「未アンロックレシピがどう出るか」を実測する。
3. **依存の runtime ロード**: build.gradle は litematica に倣い plain `implementation`。
   `runClient` で malilib/litematica が mod ロードされない場合は `modImplementation` へ切替か run/mods 配置。
4. **マッピング行**: loom 1.17 が mojmap を default 供給する前提。失敗時は `mappings loom.officialMojangMappings()`。
5. **未コンパイル**: この scaffold はまだ `gradlew build` を通していない (JDK 25 環境前提)。
   malilib API 名 (`JsonUtils.parseJsonFile`, `ConfigUtils.readConfigBase`, `IHotkeyCallback#onKeyAction`,
   `InfoUtils.showGuiOrInGameMessage` 等) は litematica/malilib 0.29.0 のソースに合わせたが、要ビルド確認。
6. **config 画面**: 未登録。hotkey の GUI リバインドを可能にするなら `Registry.CONFIG_SCREEN` 登録が必要。

---

## 2026-06-20 ビルド検証

`./gradlew build` (JDK 25) で **BUILD SUCCESSFUL**。`rawmats-fabric-26.2-0.1.0.jar` 生成。

確定した事項 (上の仮説の検証):
- **mappings 行不要が確定**: build.gradle に mappings を書かずに mojmap で通った
  (loom 1.17 が default 供給する、の仮説が裏取りできた)。→ TODO #4 解消。
- **コンパイル成功 = 使用 API 名が正しい**: malilib/litematica 0.29.0/0.28.0 に対し、
  `JsonUtils.parseJsonFile(Path)`, `ConfigUtils.readConfigBase/writeConfigBase`,
  `IHotkeyCallback#onKeyAction(KeyAction, IKeybind)`, `InfoUtils.showGuiOrInGameMessage`,
  `new ItemStack(Holder<Item>)`, `MaterialListJson.readMaterialListAll`, `getEntriesCombined()`,
  `MaterialListEntry(ItemStack,int,int,int,int)` 等が全て解決。→ TODO #5 (API 名) 解消。
- 追加で必要だった repo: **`https://maven.fallenbreath.me/releases`**。
  malilib/litematica が `me.fallenbreath:conditional-mixin-fabric:0.6.4` を推移的に要求するため。
  (初回ビルドはこれが無くて `:mainRuntimeDependencyResolve` で失敗 → repo 追加で解決。)

未検証 (実機起動が必要、ここでは不可):
- ~~runtime での mod ロード~~ → 下記 runClient で解消。
- in-game 挙動: SMP でのアンロック依存 (TODO #2)、展開粒度 packed/raw (TODO #1)。

### runClient (dev 起動) 検証

- **TODO #3 解消**: `./gradlew runClient` で Fabric が 11 mods をロードし、
  `litematica 0.28.0` / `malilib 0.29.0` / `rawmats 0.1.0` が揃った。
  plain `implementation` でも dev ランタイムに mod として載る (modImplementation 不要)。
  依存解決エラー無し、クライアントはタイトル画面まで到達。
- **環境メモ (このマシン固有)**: 初回 runClient は **メモリ commit 不足で失敗** (DOS error 1455,
  ページファイル過小)。物理 RAM 128GB だが稼働中アプリ (Ableton 2 本で ~45GB commit 等) で
  commit 残 ~5GB。runClient は gradle JVM + フォークしたクライアント JVM の二重確保になるため、
  `org.gradle.jvmargs=-Xmx1g` + loom `client { vmArgs "-Xmx1536m" }` に下げて解決。
  → 大きな schematic を扱うならクライアントヒープを上げる (commit 残と相談)。
- まだ未確認 (実際の機能動作): material list を開いて `M,R` → 原材料ビューが出るか、
  展開粒度、SMP アンロック依存 (TODO #1, #2)。

---

## 2026-06-20 v0 実機フィードバック → 折りたたみクラフトツリーへ再設計

### 実機で判明したこと (事実)

- ホットキー既定 `M,R` は litematica の `toggleAllRendering` と衝突 → **`M,K` に変更**。
- v0 (combined/repack 利用) の問題:
  1. **過剰展開**: nether quartz はクラフト不可 → furnace 経路で「quartz ore を集めろ」になる。
  2. **在庫が一致しない/無視されて見える**: `repackCombinedEntries` が base 素材を block に再圧縮
     (`packingOverrides`: REDSTONE→REDSTONE_BLOCK ×1/9, IRON_INGOT→IRON_BLOCK ×1/9 …) するため、
     redstone block を dust で見たいのに block に戻り、インゴット所持でも表示が block で在庫と合わない。
  3. **系譜が混ざる**: `MaterialListJsonCache` が `combineSteps/combineResults` で全合算するため、
     鉄ブロック用とピストン用の鉄インゴットの区別が消える。

### 鍵 (事実)

- 再帰ツリーは **`MaterialListJsonBase` 自身**が保持 (`MaterialListJsonEntry.getRequirements()` → 子)。
  合算は Cache のみ。→ **ツリーを直接消費すれば系譜は自然に分かれる**。
- `MaterialListJsonOverrides.shouldKeepItemOrBlock` (UNPACKED_BLOCK_ITEMS = ingot/nugget) が leaf 化 →
  **鉄はインゴットで止まりナゲットまで行かない**。quartz→ore だけが furnace 経路で起きる過剰展開。
- 「可逆なら先祖返り」のデータも既存 (`packingOverrides` 9→1/4→1, `overrides` 1:1, `checkIfLoop`)。

### 決定 (ユーザー選択)

- 系譜/集計: **ツリー + マージ切替**。
- 初期展開: **畳んだ状態から開始、各ノードを展開も折りたたみも可能に**。
- → repack を使う Cache 経路は捨て、**`MaterialListJsonBase` ツリーを直接表示**。過剰展開は
  「畳み初期 + 必要時だけ展開」で回避 (quartz を展開しなければ ore は出ない)。

### v0.2 アーキテクチャ (実装済み・ビルド通過)

- `materials/CraftNode` — `MaterialListJsonBase` をラップした表示用ノード (item/count/depth/children/expanded)。
- `materials/CraftTree` — root = source.getMaterialsAll() を各々 `new MaterialListJsonBase(...)` で再帰構築。
  `getDisplayNodes()` が「可視ノード DFS」or「frontier 合算 (買い物リスト)」を返す。在庫マップ保持。
  - **frontier = 展開されていないノード** = 実際に集める単位。展開で材料に置換、畳みで先祖返り。
- `gui/GuiCraftTree` (GuiListBase 継承) + `WidgetListCraftTree` (WidgetListBase, shouldSortList=false) +
  `WidgetCraftTreeEntry` (WidgetListEntryBase, 行クリックで展開/畳み、indent で階層表現)。
  ボタン: Merge 切替 / Expand all / Collapse all。
- **mixin 不使用・専用 GUI**。v0 の `RawMaterialList` + GuiMaterialList 流用は廃止 (ファイル削除)。
- `gradlew build` 成功 (jar 23KB)。**実機 (runClient) での挙動は未確認**。

### 残 TODO

- 在庫の「先祖返り」整合: インゴット所持時に「鉄ブロック」ノードへどう反映するか (cross-form credit) は未対応。
  今は各ノードの exact item で在庫照合するのみ。
- multiplier 未対応。検索バー無し。config 画面 (hotkey リバインド GUI) 未登録。
- 可逆ノードの「先祖返り」専用操作 (現状は親を畳めば戻るだけ。block↔ingot の任意方向圧縮は未提供)。
- 大規模 schematic でのツリー一括構築コスト (現状 eager 全展開)。

---

## 2026-06-20 v0.3 「編集可能なフラット買い物リスト」へ再設計 (実機フィードバック2巡目)

### ユーザー追加要望 (5点) と対応方針

1. merge 時、親が在庫で全充足なら子は非表示 → **在庫ネットで展開停止**。
2. 基本は普通の material list、必要な物だけ展開 → **既定フラット(全畳み) + 行ごと展開**。
3. 展開はツリーでなく並列 → **in-place 置換**(インデント廃止、同一アイテム合算)。
4. 共有素材 (plank は stick/piston 両用) を畳むとき親を選択 → **「展開中」チップで親単位に畳む** (Phase 1)。
   素材クリックで親選択 (option 2) は Phase 2。
5. stick=oak/birch の置換 → **Phase 2** (タグ材料の置換)。

### 決定 (ユーザー選択)

- 畳み操作 = **「展開中」チップ一覧から** (Q1 option 1)。option 2 も将来やる。
- 在庫充足項目 = **隠さずその時点で展開停止** (緑で「0 (在庫N)」表示)。

### モデル (v0.3, 実装済み・ビルド通過)

- 状態 = **展開したアイテム種別の集合** (`CraftTree.expanded`, 既定空)。
- 表示 = baked ツリー (`MaterialListJsonBase`→`CraftNode`) を walk し、
  展開対象は子へ in-place 置換、それ以外は frontier 葉として**同一アイテムで合算 → フラット** (`MatRow`)。
- 在庫ネット: walk 中に在庫 budget を引き当て、**完全充足ノードは展開停止** (covered=leaf)。
- GUI: `GuiCraftTree` (Collapse all / Expand all + 「展開中」チップ) /
  `WidgetCraftTreeEntry` (フラット 1 行、`[+]` クリックで `tree.expand(item)` → `reInit()`)。
- v0.2 のインデントツリー描画・Merge トグルは廃止 (フラット合算が既定)。
- `gradlew build` 成功。runClient で 11 mods ロード確認。**機能の実挙動は未検証**。

### Phase 1 の割り切り (要 Phase 2)

- **部分在庫スケーリング未対応**: 完全充足は展開停止するが、半端在庫の item を展開すると子は baked の gross 個数のまま
  (shortfall で子を按分しない)。
- 在庫引き当ては DFS 先着順 (共有素材の配分は素朴)。
- チップは 1 行に収まらない分を省略 (Collapse all で回収)。
- ④の素材クリック→親選択 (option 2)、⑤のタグ材料置換 (oak↔birch) は未実装。

---

## 2026-06-20 v0.4 畳みを「行クリック→一時ポップアップ」に (option 2)

### 変更 (ユーザー要望)

- チップバー廃止 (数が増えると 1 行から溢れる問題)。
- 畳み操作を **option 2**: frontier 素材の行をクリック → カーソル位置に**一時ポップアップ**メニュー。
  そのアイテムに適用できる操作 (Expand / Fold into <親>…) を提示し、選んで実行。
  plank のように複数親 (stick/piston) から来る素材は「Fold into stick」「Fold into piston」が並ぶ。

### 実装

- `materials/CraftTree`: 子→親の逆引き `childToParents` を baked ツリーから構築。
  `getCollapseCandidates(child)` = child を直接の子に持つ「展開中の親」一覧。`hasCollapseCandidate` も。
  `MatRow` に `foldable` 追加。
- `gui/WidgetCraftMenu` (新, `WidgetContainer` 派生): エントリ = (label, Runnable)。
  ボタン群を縦に並べ、背景ボックス描画。`WidgetContainer` が自範囲内のクリックをボタンへ分配。
- `gui/GuiCraftTree`: `popup` フィールド。`openMenu(row, x, y)` で候補からメニュー生成 (画面内クランプ)。
  `onMouseClicked` を override し、popup 表示中は overlay にクリックを横取り (範囲外クリックで閉じる)。
  `drawContents` で list の後に popup を描画。ESC で閉じる。`onMenuActionDone()` で実行後に閉じ + reInit。
- `gui/WidgetCraftTreeEntry`: 左クリックで `openMenu`。マーカー `[+]`/`[-]`/`[±]` で展開可/畳み可/両方を表示。
- `gradlew build` 成功。runClient ロード確認。**実挙動は未検証**。

### 残 (Phase 2 継続)

- 部分在庫スケーリング (半端在庫の item を展開時に子を按分)。
- ⑤ タグ材料の置換 (stick=oak/birch を選ぶ)。
- メニューに「子個別の Fold」等の粒度 (現状は親種別単位)。

---

## 2026-06-20 v0.5 左右クリック / 単一即実行 / fold をアイコン提示

### 変更 (ユーザー要望)

- **左クリック = 展開、右クリック = fold**。
- **対象が 1 つなら即実行**(展開は常に単一なので即時。fold も候補 1 つなら即時)。
  **複数のときだけポップアップで尋ねる**。
- fold の選択肢は**名前でなくアイコン**で提示。

### 実装

- `gui/WidgetCraftMenu`: ButtonGeneric をやめ、**アイコンセルを自前描画 + クリック判定**(縦並び)。
  ホバーで右側にアイテム名を表示 (`drawButtonHoverTexts` は WidgetContainer に無いため自前)。
  Entry = (`Holder<Item>`, Runnable)。
- `gui/GuiCraftTree`: `openMenu` を廃し `expandRow` / `foldRow` に分離。
  foldRow は候補 1 → 即 collapse、複数 → アイコンポップアップ。
- `gui/WidgetCraftTreeEntry`: `click.input()==0` で expandRow、`==1` で foldRow。
- `gradlew build` 成功。

### 注意 / 未確認

- 右クリックが list entry まで伝播するか要実機確認 (GuiBase が右クリックを食わない前提)。届かなければ要調整。
- 残: 部分在庫スケーリング、⑤ タグ材料置換 (oak↔birch)。

---

## 2026-06-20 v0.6 タグ材料の置換 (Phase 2 ⑤)

### 事実: Litematica のタグ材料解決ロジック

- レシピ材料の解決は `MaterialListJsonEntry.build()` (`:171-188`) の中。ingredient が複数候補
  (タグ = any planks 等) のとき `ingEntries.size() > 1` の分岐で
  `overridePrimaryMaterial(ingEntries.get(0))` を呼び、**先頭候補 1 つに正規化**している。
- `MaterialListJsonOverrides.overridePrimaryMaterial` (`:148`) は wool/bed/glass/concrete 等を
  特定の色 (white) に寄せる分岐を持つが、**planks には分岐が無い** → matchOverride も該当せず、
  `ingEntries.get(0)` (タグ登録順の先頭 = 通常 oak) がそのまま採用される。これが「stick=oak」の正体。
- この選択ロジックに介入する公式の口は無い。`MaterialListJsonOverrides.INSTANCE` は `public static final`
  だが内部の overrides は private、メソッドは protected。→ アドオンからは直接いじれない。
- ただし元 `Ingredient` は `MaterialListJsonEntry.getRecipeRequirements()` (`primaryId` で引く) に残る。
  各 ingredient の候補一覧は malilib の `((IMixinIngredient) ing).malilib_getEntries()` (HolderSet) で取得でき、
  タグキーは `HolderSet.unwrapKey()` (Named なら present) で取れる。
  - `IMixinIngredient` は **malilib が公開している mixin accessor** (Ingredient.values の @Accessor)。
    これを呼ぶだけで、アドオン自前の mixin 追加では無いので「mixin 無し」方針は維持。

### 決定 (ユーザー選択)

- **粒度 = タグ単位グローバル**。「any planks → birch」のようにタグ単位で一括指定。そのタグ由来の材料は
  全箇所で同じ具体素材になる。箱所 (親) 別は現行フラット合算 UI (同一アイテム 1 行) と非整合のため非対応。
- **具体材料要求は対象外で固定**。例: schematic が birch_trapdoor を要求 → そのレシピ材料 birch_planks は
  タグでなく具体指定 (候補 1 つ) なので置換 UI が出ず固定計上。タグ材料 (any planks の stick 等) だけが選択可能。
  両者はフラット合算で正しく足し合わさる (oak のままなら別行、birch を選べば birch_planks 行に合流)。
- **操作 = 中クリック**。左=展開 / 右=畳む は既存。中クリックで候補アイコンポップアップ。

### 実装 (ビルド通過)

- `materials/CraftNode`: `choiceKey` (TagKey) / `choices` (候補一覧) を追加。`fromBase` を改修し、
  各子について「候補複数 (タグ) で、解決済み item を候補に含む ingredient」(`findTagIngredient`) を逆引き。
  該当すれば候補/タグキーを保持し、`materialOverride` に選択があれば
  `new MaterialListJsonBase(override, count, parent, craftingOnly)` でサブツリーを作り直す
  (count は同種レシピ前提で維持)。`isChoosable()` 追加。
- `materials/CraftTree`: `materialOverride` (タグ→具体) と `source` を保持。`chooseMaterial(key, item)` で
  override 更新 → `rebuild` (展開状態は維持)。`walk` で frontier の choosable ノードを集約し `MatRow` に乗せる。
- `materials/MatRow`: `choosable` / `choiceKey` / `choices` を追加。
- `gui/GuiCraftTree`: `chooseRow` 追加 (候補を WidgetCraftMenu で提示)。ポップアップ生成を `openPopup` に共通化。
- `gui/WidgetCraftTreeEntry`: 中クリック (`input()==2`) で `chooseRow`。choosable 行に金色 `[*]` マーカー。
- `gradlew build` 成功。**実機 (runClient) での挙動は未検証** (in-game で schematic を読み込む操作が必要)。

### 注意 / 未確認 / 残

- `findTagIngredient` は「同一 item を候補に含む複数候補 ingredient」の最初の 1 つで対応付ける素朴版。
  同一 item が複数の異なるタグ ingredient から来る稀ケースの曖昧性は未考慮。
- `unwrapKey()` が empty (Direct HolderSet = タグでなく直接列挙の複数候補) のケースは選択対象外で固定。
  planks/wool 等は Named タグなので対象になる想定だが、実機で choosable 判定の出方は要確認。
- override 後のサブツリー再構築で、選択素材のレシピ収量が元と異なる場合の count ズレは未対応 (同種レシピ前提)。
- 中クリックが list entry まで伝播するか要実機確認。`[*]` マーカーが長い名前で need 表示と重なる可能性。
- 残 Phase 2: 部分在庫スケーリング、fold 粒度、UX (multiplier/検索/config 画面)、性能、SMP 完全性 (方針 B)。

## 参照 (ローカル clone)

- `C:/Users/naari/src/github.com/sakura-ryoko/litematica` (branch 26.2)
- `C:/Users/naari/src/github.com/sakura-ryoko/malilib` (branch 26.2)
- 主要ファイル: `materials/json/MaterialListJson*.java`, `gui/GuiMaterialList.java`,
  `gui/widgets/WidgetMaterialListEntry.java`, malilib `util/game/RecipeBookUtils.java`
