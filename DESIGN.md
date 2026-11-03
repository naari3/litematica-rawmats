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
   → v0.7 で着手・実機 OK (下記)。不足分 net を再 build して収量反映済み。
   残: 在庫引き当ては依然 DFS 先着の素朴版 (共有素材の配分)。
2. **タグ材料の置換 (⑤)**: stick=oak/birch 等、タグ材料 (any planks 等) でどの具体素材を使うか選択。
   現状は Litematica の `overridePrimaryMaterial` 由来の自動選択。per-item の override を持たせる想定。
3. **fold 粒度**: 現状は親「種別」単位で畳む。子個別 (per-edge) の fold 等のより細かい操作。
4. **UX 各種**: multiplier (Litematica 同様の倍率)、検索バー、hotkey リバインド GUI
   (`Registry.CONFIG_SCREEN` 登録 + config 画面)。
   → 実装プラン確定 (2026-06-20、下記 v0.8 節)。UI はできるだけ本家 Litematica に寄せる。
   加えて (C) 展開ステートのセッション内保持、(D) 共有用プレーンテキスト書き出しを含める。
   着手順 = C → A(multiplier) → D → B(本家寄せ仕上げ)。
   → **C・A・D・B すべて実装済み・実機 OK** (下記 v0.8 各節)。④ UX は一通り完了。
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

## 2026-06-20 v0.7 部分在庫スケーリング (Phase 2 ①)

### 変更 (ユーザー要望)

- 半端に在庫がある item を展開したとき、子を「不足分」で按分する。
  現状 (Phase 1) は完全充足のみ展開停止、部分展開は baked の gross 個数のままだった。

### 実装 (ビルド通過・実機 OK)

- `materials/CraftNode`: `parentItem` を追加 (root なら null)。`fromBase` で各子に親 item を保持
  (再 build 時の `prevItem` = `checkIfLoop` のループ検出に使う)。
- `materials/CraftTree`: `walk` で frontier 展開時、在庫を引いた残 `net < n.count` のノードは
  `new MaterialListJsonBase(n.item, net, n.parentItem, this.craftingOnly)` で**不足分だけのサブツリーを
  再構築**し、`build()` の収量計算 (1 log → 4 planks 等) を正確に反映。在庫ゼロ (net == count) は
  baked をそのまま使う。
- commit `7bccd08`。

### 実機検証 (2026-06-20)

- runClient で動作確認、**いいかんじ** (部分在庫の不足分按分が期待通り)。

### 残

- 在庫引き当ては依然 **DFS 先着順の素朴版** (共有素材の配分は雑)。
- 残 Phase 2: ③ fold 粒度、④ UX (multiplier/検索/config 画面)、⑤ 性能、⑥ SMP 完全性 (方針 B)。

---

## 2026-06-20 v0.7 部分在庫スケーリング (Phase 2 ①) 実機 OK

### 実装 (commit 7bccd08)

- `materials/CraftNode`: `parentItem` を追加 (root なら null)。`fromBase` で各子に親 item を保持
  (再 build 時の `prevItem` = `checkIfLoop` のループ検出に使う)。
- `materials/CraftTree`: `walk` で frontier 展開時、在庫を引いた残 `net < n.count` のノードは
  `new MaterialListJsonBase(n.item, net, n.parentItem, this.craftingOnly)` で不足分だけのサブツリーを
  再構築し、`build()` の収量計算 (1 log → 4 planks 等) を反映。在庫ゼロ (net == count) は baked をそのまま使う。

### 実機検証

- runClient で動作確認、良好。部分在庫の不足分按分が期待通り。

### 残

- 在庫引き当ては依然 DFS 先着順の素朴版 (共有素材の配分は雑)。

---

## 2026-06-20 Phase 2 ③ fold 粒度 — 見送り決定

### 観察 (コード確認)

- 展開状態は `CraftTree.expanded: Set<Holder<Item>>` ＝ アイテム「種別」単位。`expand`/`collapse` も種別単位。
  `walk` は `expanded.contains(it)` で判定するため、同一アイテムは全出現箇所で展開状態が一致する。
- ただし fold 操作は `getCollapseCandidates(child)` (CraftTree.java:176) が「その子を直接持つ展開中の親」
  一覧を返し、親を選んで `collapse(parent)` する形 (v0.4)。
  → planks の行で「Fold into stick」を選べば stick 経由だけ畳まれ、piston 経由は残る。
  「片方の親だけ畳む」は既に可能。

### 訂正

- 検討中に一度「planks を畳むと両経路が同時に畳まれる」と述べたが誤り。上記の通り親選択で片方だけ畳める。

### 残る種別単位の限界 (実害小と判断)

1. expand 側: アイテム展開は全出現箇所で同時。「piston 下の iron_ingot だけ展開」は不可。
2. 多用途中間材料の fold: stick が torch/rail 等の子でもある場合、`collapse(stick)` は stick の全出現を畳む。

### 決定

- per-edge 展開状態 (エッジ/パス単位) への拡張はフラット合算 UI と本質的に緊張し、コストに見合わないと判断。
  ③ は見送り・据え置き。1・2 が実機で実害になった時点で再検討する。

---

## 2026-06-20 v0.8 実装プラン (Phase 2 ④ + ステート保持 + 共有書き出し)

ユーザー決定の着手順: **C → A → D → B**。

### C. 展開ステートのセッション内保持

- 課題: `KeyCallbacks.onKeyAction` が毎回 `new CraftTree(source)` (KeyCallbacks.java:45)。開くたびに
  `expanded` / `materialOverride` / (今後の) multiplier が初期化される。
- 方針 (ユーザー決定 = **セッション内のみ**。ディスク永続はしない):
  状態を `source` から分離して static に保持し、同じ material list を開き直したら復元。別 schematic は別状態。
  source 同一性のキー (schematic 名 or material list 参照) は実装時に確定。MC 再起動で消えてよい。

### A. multiplier (④ の中核)

- 本家 `GuiMaterialList` と同様に上部へ倍率入力。`source` 総数 × multiplier を `CraftTree` 構築時に反映。
  展開状態・タグ選択・在庫ロジックは不変。実装は軽い。

### D. 共有用ファイル書き出し (プレーンテキスト)

- 形式 (ユーザー決定 = **プレーンテキスト**)。現在の `getRows()` (item / need / 在庫 / 選択素材 / multiplier)
  を人が読める整形テキストで出力 (コードブロックに貼れる汎用形式)。
- GUI に Export ボタン (本家 WRITE_TO_JSON の位置を踏襲) か専用ホットキー。出力先は Litematica config フォルダ配下。
- 内容に multiplier・在庫差引・選択素材・生成日時を含める想定。

### B. UI を本家に寄せる (仕上げ)

- 列レイアウト・配色・列ソートを本家 `WidgetMaterialListEntry` 寄りに。missing のみ表示トグル等も踏襲。
- 専用機能 (展開/畳み/タグ選択マーカー) は残す。A の後の段階的作業。

### 未着手 (今回スコープ外)

- ① の在庫引き当て DFS 先着改善、⑤ 性能 (eager 全構築の遅延化)、⑥ SMP 完全性 (方針 B)。

---

## 2026-06-20 v0.8 C (ステート保持) + A (multiplier) 実装

### C. 展開ステートのセッション内保持 (実装・ビルド OK)

- 状態を source から分離: `materials/CraftTreeState` (新) = `expanded` (展開集合) /
  `materialOverride` (タグ→具体) / `multiplier` の入れ物。
- `materials/CraftTreeStore` (新) = source キー → CraftTreeState の static `HashMap`。
  `CraftTreeStore.get(source)` で取得/新規。
- **source 同一性キーの決定 = 内容ベース (`class名 + getTitle()`)**。インスタンス参照ではない。
  - 根拠: material list を Load GUI から開くたびに `new MaterialListSchematic(...)` で**別インスタンス**になる
    (GuiSchematicLoad.java:214)。ホットキー再押下では DataManager が同一インスタンスを保持するので
    インスタンス参照でも足りるが、Load GUI からの作り直しでは復元されない。
  - `getTitle()` は schematic 名・種別 (schematic/placement)・リージョン数まで含む
    (MaterialListSchematic.getTitle())。「全リージョン」と「部分選択」は別キーになる。同名 schematic 衝突は
    実害小と判断 (許容)。
  - 訂正の余地: getTitle() は翻訳テキスト依存。セッション内一貫性のみ要るので locale 差は問題ない。
- `CraftTree` を state 委譲に改修: `expanded`/`materialOverride` フィールドを廃し `CraftTreeState` を参照。
  コンストラクタ `CraftTree(MaterialListBase, CraftTreeState)`。`KeyCallbacks` (KeyCallbacks.java:45) で
  `CraftTreeStore.get(source)` を渡す。
- 効果: 同じ material list を開き直すと展開状態・タグ選択・倍率が復元される。別 schematic は別状態。
  ディスク永続なし (MC 再起動で消える、ユーザー決定どおり)。

### A. multiplier (実装・ビルド OK)

- `CraftTreeState.multiplier` を `CraftTree.rebuild` の総数計算に反映
  (`total = stack.count * countTotal * mult`)。`CraftTree.getMultiplier/setMultiplier` (1 以上クランプ、
  変化時のみ rebuild)。
- `gui/GuiCraftTree`: 本家 `GuiMaterialList` に寄せて**右上に倍率入力**
  (`GuiTextFieldInteger` + `MultiplierListener implements ITextFieldListener`)。変更時は
  `getListWidget().refreshEntries()` のみ (reInit しない = 入力フォーカス維持、本家と同じ作法)。
- lang: `rawmats.gui.label.multiplier` (Multiplier: / 倍率:) を追加。
- **本家の `MaterialListBase.multiplier` とは独立**。rawmats 側の倍率は state 由来で既定 1。
  Litematica 側で倍率を設定済みでも継承しない (スコープを明確化)。要望が出れば初期値継承を検討。

### 未検証 (実機 runClient が必要)

- C: ホットキー再押下 / Load GUI 再オープンの両方で状態が復元されるか。別 schematic で混ざらないか。
- A: 倍率入力で全行 (在庫差引・タグ選択込み) が正しくスケールするか。倍率と部分在庫按分の相互作用。
- 残: D (共有用プレーンテキスト書き出し)、B (UI を本家に寄せる仕上げ)。

---

## 2026-06-20 v0.8 D (共有用プレーンテキスト書き出し) 実装

### 実装 (ビルド OK)

- `materials/CraftListExport` (新): `CraftTree` の現在の表示行 (`getDisplayRows`) を整形した String を返す
  `render(tree)`。内容 = ヘッダ (source タイトル + 倍率 + 生成日時 RFC1123)、`Need / Have / Item` の
  右寄せ表 (コードブロックに貼れる等幅整形)、末尾に「Selected materials」(ユーザーが選んだタグ置換のみ)。
  アイテム名は `new ItemStack(holder).getHoverName().getString()`、タグは `TagKey.location()` を `#...` 表記。
- `CraftTree`: 書き出し用 getter `getSourceTitle()` / `getMaterialOverrides()` を追加。
- `gui/GuiCraftTree`: 上部ボタンに **Export** を追加。`exportToFile()` で
  `FileUtils.getConfigDirectory()/rawmats/raw_material_list_<REGULAR ts>.txt` に `Files.writeString`、
  成功時 `addMessage` + `StringUtils.sendOpenFileChatMessage` (チャットに開けるリンク)。失敗は ERROR メッセージ。
- lang: `rawmats.gui.button.export` / `rawmats.message.exported` / `rawmats.message.export_failed` を追加。
- 出力順は現状の表示順 (DFS、ソートなし)。ソートは B (UI 仕上げ) で検討。

### 未検証 / 残

- 実機: Export 実行でファイル生成・チャットリンク・内容 (倍率/在庫差引/選択素材) が期待どおりか。
- 残 = **B (UI を本家 WidgetMaterialListEntry に寄せる仕上げ)**。列レイアウト・配色・列ソート・missing のみ
  表示トグル。専用機能 (展開/畳み/タグ選択マーカー) は残す。これは見た目の調整なので実機で見ながら詰める想定。

---

## 2026-06-21 「block of redstone が展開できない」調査 → 在庫吸収による正常動作

### 観察 (ユーザー報告)

- 「全展開 (Expand all) しても block of redstone が展開できないように見える」。

### 調査: 構造ダンプログを追加 (恒久的に残す)

- `CraftTree.logStructure()` を追加 (`GuiCraftTree` の **Expand all 押下時**に呼ぶ)。ベイク済みツリー (item/count/子数/
  expandable/choosable) を再帰ダンプし、leaf については `RecipeBookUtils.getDisplayEntryFromRecipeBook` を
  直接引いて `recipeBookLookup` (book にレシピがあるか: -1=level/player null, 0=無い) を出す。
  さらに `getDisplayRows()` の結果 (need/have/expandable/foldable/choosable) もダンプ。
- 実機ログ (2026-06-21) の該当行:
  - `redstone_block x1 children=1 expandable=true` → 子 `redstone x9`。**正しく分解される**。
  - `redstone ... children=0 recipeBookLookup=3` → recipe book にレシピはある (3 件) が、loop 検出
    (redstone↔redstone_block) で leaf。基礎素材として正しい。
  - 他: iron_ingot/gold_ingot/coal=recipeBookLookup3、stone/slime_ball/cobblestone=1、oak_log/bamboo/dirt/
    obsidian/nether_quartz_ore=0 (= 生素材で正しく leaf)。

### 結論 (ユーザー確認済み)

- バグではない。必要分の **block of redstone を既に在庫で所持していたため**、`walk()` の
  `covered = (net == 0)` 判定で展開停止し、緑の充足行として出ていた (= 在庫ネットの正常動作)。
- **訂正**: セッション初期に立てた「ClientRecipeBook 未アンロックで展開されない (TODO #2)」仮説は、
  この件には該当しなかった。この SP world では recipe book に各レシピが入っており (recipeBookLookup>0)、
  redstone_block も正しく展開された。未アンロック依存自体は別 world/SMP で要検証のまま据え置き。

### ログの扱い

- 構造+表示行ダンプは有用なので**恒久的に残す**。明示操作のときだけ出したいので **Expand all 押下時**に出力
  (= 全展開状態 = 原材料への完全分解 + 合算後フラットリストが一度に取れる)。M,K 開時の自動ログは外した。
- FQN を import に整理済み。将来ノイズが問題になれば config でトグル化を検討。

---

## 2026-06-21 v0.8 B (UI を本家に寄せる) 実装・実機 OK

本家 `WidgetMaterialListEntry` / `GuiMaterialList` / `WidgetListMaterialList` に寄せた。専用機能
(展開/畳み/タグ選択マーカー) は維持。実機 (runClient) でユーザー確認済み「いい感じ」。

### 実装

- **4列レイアウト**: `アイテム (アイコン+名前) | 総数 | 不足 | 在庫`。列幅は内容から自動算出
  (`WidgetCraftTreeEntry.setMaxLengths`、本家の maxNameLength 方式)。行高 22。
  - `MatRow` に gross `total` を追加 (在庫差引前)。`walk` で `acc[0]=net(missing)`, `acc[1]=gross(total)` を集計。
  - 列 0 左端に展開/畳みマーカー用の幅 (MARK_W=14) を予約。`[+]`/`[-]`/`[±]`/`[*]` は維持。
- **配色** (本家と同じ): 不足 = 充足(0)緑 / 在庫で賄える金 / 不足赤。在庫 = 足りてれば緑・不足赤。
- **列ソート**: `WidgetCraftTreeEntry extends WidgetListEntrySortable`。スクロールしないヘッダ行 (row==null,
  `createHeaderWidget`) に列タイトル + ソート矢印。列クリックで Name/Total/Missing/Available 切替
  (同列再クリックで昇降トグル、本家 setSortCriteria に倣う)。ソート状態は `CraftTree` に保持 (GUI 開いてる間)。
- **「在庫済みを隠す」トグル** (本家 hide_available 相当、ButtonOnOff): need<=0 の充足行を隠す。
- **検索バー**: `WidgetListCraftTree` に `WidgetSearchBar` を設定 + `getEntryStringsForFilter` (名前 / 登録 ID)。
  検索オープン時はヘッダを隠す (重なり回避)。`|` 区切り OR。
- **下部の集計情報**: 「N 種 · 必要 M · 完了 X.X%」(完了率 = (総数−不足)/総数)。書き出しボタンを下部左へ移動
  (本家 write 系の位置)。
- **ホバーツールチップ**: `postRenderHovered` で アイテム名 + 総数/不足/在庫 を計算式
  「N = S × 64 + R = X.XX SB」付きで表示 (本家 getFormattedCountString と同じ、maxStackSize 考慮)。
- lang (en/ja): 列タイトル・hide トグル・集計・SB 略称を追加。

### 残 (任意・要望次第)

- ツールチップ位置の微調整。

---

## 2026-06-21 v0.8 B+ 行の ignore + ソート/hide のセッション永続

### 実装 (ビルド OK・実機確認: 正常終了/例外なし)

- **行の ignore (本家 per-row 除外)**: 各行右端に「除外」ボタン (`WidgetCraftTreeEntry` の subWidget、
  `WidgetContainer` のボタン分配で動作)。押すと `CraftTree.ignore(item)` でその種別を除外。
  上部に「除外を解除」(`clearIgnored`) を、除外が1件以上あるときだけ表示。除外行は `getDisplayRows` でスキップ。
- **ソート/hide/ignore のセッション永続**: `sortColumn` / `sortReverse` / `hideAvailable` を `CraftTree` の
  一時フィールドから `CraftTreeState` に移動、`ignored` 集合も追加。これで展開状態・倍率・タグ選択と同様、
  同じ material list を開き直すと並べ替え・hide・除外まで復元される (MC 再起動でクリアは従来どおり)。

### 残

- ツールチップ位置の微調整 (任意)。

---

## 2026-06-21 ① 在庫引き当てをグローバル集計に改修・実機 OK

### 変更前 (DFS 先着) の問題

- root 順 DFS で各ノードがその場で在庫 budget を先取り → 部分在庫が展開可能な中間素材を賄うとき
  「どの枝が covered/展開されるか」が配置依存。
- 部分在庫ノードを出現ごとに再 build → 収量>1 レシピ (planks=4, stick=4 等) で切り上げが出現数だけ
  重なり過大計上しうる。
- (フラットな base 葉の合計値は budget 持ち越しで元々正しかった。)

### 変更後 (型ごとグローバル集計) — `CraftTree.getDisplayRows` 全面書き換え

1. 展開対象 (expanded ∩ expandable) の型を **型 DAG (`typeChildren`) の親→子 topo 順** で処理
   (`topoExpandedTypes` / `topoVisit`)。
2. 各型の総需要を一度だけ集計 (`pendingGross`、`addGross` で展開対象は pending・他は frontier に振り分け)。
3. 型ごとに在庫を1回だけネット (`toCraft = max(0, 総需要 − 在庫)`)。完全充足は covered (frontier 行・need 0)。
4. 不足分 `toCraft` を**一度だけ分解** → レシピ収量の切り上げが総量に対して1回。
5. frontier を在庫ネットして行に。fold/展開フラグ/タグ選択 (choosable)/sort/hide/ignore は不変。

旧 `walk` は削除。型 DAG は `indexNode` で `typeChildren` を構築。

### 効果 (実機: 回帰なし・数字 OK 確認)

- 在庫配分が DFS 到達順に非依存 (配置を変えても結果が一致)。
- 中間素材の出現別切り上げ過大が解消。

### 残課題: ⑤ 性能 → 下記で第一弾対応

---

## 2026-06-21 ⑤ 性能 第一弾 (ログ軽量化 + 行キャッシュ) → 実機 ≈1/3 に

### 切り分け

- Expand all ≈1 秒の主因:
  - (a) デバッグログ (`dumpNode`) が **leaf ごとに `getDisplayEntryFromRecipeBook` = recipe book 全走査**。
  - (b) `getDisplayRows` が呼ばれるたびに展開対象を `new MaterialListJsonBase` で分解 (これは内部で
    **サブツリー全体を再帰構築** = 各ノードで recipe book 走査)。reInit ごとに ~3 回 (summary / list / log)。

### 対応 (実機: ≈1/3 の時間に短縮)

- (a) `dumpNode` の leaf ごと recipe book 走査を**除去** (redstone 調査用で役目終了。構造 + 表示行ダンプは残す)。
- (b) `getDisplayRows` の結果を**キャッシュ** (`cachedRows` / `rowsDirty`)。状態変化 (展開/在庫/ソート/hide/
  ignore/倍率/タグ、= rebuild と各 mutator) 時のみ再計算。reInit 内の重複呼びと毎フレーム再計算を排除。

### 残 (任意・第二弾)

- `getDisplayRows` 1 回の計算自体はまだ「展開対象ごとに `new MaterialListJsonBase` でサブツリー全体を再帰
  構築」しており、Expand all では型数分のフルビルド (recipe book 走査多数) が走る。
  「**1 階層だけ分解**」(レシピ収量丸めを総量に1回で再現) にすれば型数 × 数回の走査に減らせるが、
  Litematica のレシピ選択 + 丸めの再現が要る中規模作業。≈1/3 で十分なら据え置き。

---

## 2026-06-21 config 画面 (ホットキー リバインド) + lang キー形式の修正

### 構成 (本家 litematica/malilib に寄せる)

- `GuiConfigs extends GuiConfigsBase`。`getConfigs()` = `ConfigOptionWrapper.createFor(Hotkeys.CONFIG_LIST)`。
- 開き方は 2 経路:
  - hotkey `M,C` (`Hotkeys.OPEN_CONFIG`)。`KeyCallbacks` でワールド外でも開けるよう world null チェックの前に分岐。
  - `Registry.CONFIG_SCREEN.registerConfigScreenFactory` で malilib の config 切替 / ModMenu からも開ける。
- 既存 litematica 設定に相乗りせず別画面にした (本家設定を汚さない / 自モッドの設定として独立)。

### バグと修正: config 画面のラベルが全部「生キー名」表示だった

- 観察 (事実): config 画面は開けるが、項目名が `rawmats.config.hotkeys.openRawMaterialList` 等の
  キー文字列そのまま表示。material list GUI 側 (タイトル/ボタン) は正しく翻訳されていた。
- 原因: config 行ラベルは `WidgetConfigOption` → `config.getConfigGuiDisplayName()` →
  `IConfigBase.getTranslatedName()` で解決される。`ConfigBase.apply(prefix)` は翻訳キーを
  **`prefix + "." + KEY + "." + cleanName`** 形式で生成する (malilib `config/options/ConfigBase.java`):
  - `TRANSLATED_NAME_KEY = "name"`   → 表示名キー `prefix.name.<cleanName>`
  - `PRETTY_NAME_KEY = "prettyName"` → `prefix.prettyName.<cleanName>`
  - `COMMENT_KEY = "comment"`        → ホバーキー `prefix.comment.<cleanName>`
  - `cleanName` は ConfigHotkey の name 文字列そのまま (camelCase 保持。本家も `name.addSelectionBox`)。
  - `getTranslatedName()` は prefix 有り時 `getTranslatedOrFallback(key, key)`。**キー未定義だと
    フォールバックがキー文字列自体**になり、生キーがそのまま画面に出る (= 今回の症状)。
- こちらの lang は `rawmats.config.hotkeys.openRawMaterialList` / `....openRawMaterialList.comment`
  という非対応形式だったため一致せず生キー表示になっていた。
- 修正: lang キーを apply() 生成形式に合わせて改名 (en/ja 両方)。
  - `rawmats.config.hotkeys.name.openRawMaterialList` (表示名)
  - `rawmats.config.hotkeys.comment.openRawMaterialList` (ホバー)
  - openConfig も同様。実機で翻訳ラベル表示を確認。

### 訂正: openConfig の既定キー M,C → M,J (litematica と衝突していた)

- 観察 (事実): litematica のデフォルト hotkey に `openGuiSettings = "M,C"` が既にある
  (litematica `config/Hotkeys.java`)。こちらの openConfig も "M,C" にしていたため**完全衝突**。
  malilib は同一キーバインドに複数 callback を許すので、M,C 押下で litematica 全体設定と
  こちらの設定画面の両方が反応しうる実害。
- litematica が使用済みの M 系: `M,A M,BUTTON_3 M,C M,G M,L M,P M,PAGE_DOWN M,PAGE_UP M,R M,S M,T M,V` + `M` 単独。
  openRawMaterialList = `M,K` は litematica 未使用で空き (問題なし)。
- 対応: openConfig を **`M,J`** に変更 (空きキー、M,K の隣)。README も更新。

- `C:/Users/naari/src/github.com/sakura-ryoko/litematica` (branch 26.2)
- `C:/Users/naari/src/github.com/sakura-ryoko/malilib` (branch 26.2)
- 主要ファイル: `materials/json/MaterialListJson*.java`, `gui/GuiMaterialList.java`,
  `gui/widgets/WidgetMaterialListEntry.java`, malilib `util/game/RecipeBookUtils.java`

---

## 2026-06-23 クラフトツリー状態のディスク永続 (セッション越え)

これまで `CraftTreeStore` はセッション内のみ (MC 再起動でクリア) だった。Litematica 本家が
schematic 配置や material list 状態をどう永続しているか調べ、同じ仕組みに乗せて MC 再起動を跨いで
展開状態などを保持するようにした。

### 観察 (事実): Litematica 本家の永続化の仕組み

- 保存形式は全て **JSON** (gson)。`DataManager.toJson()` が placements / selections 等をまとめ、
  `MaterialListBase.toJson()` (type / sort_criteria / sort_reverse / hide_available / multiplier) も
  `SchematicPlacement.toJson()` の中に埋め込まれて保存される。
- 保存先: `config/litematica/litematica_data_[worldname].json` (world 固有) ほか。
- ライフサイクルは malilib の `IConfigHandler` 経由 (本家の `Configs` も同じ仕組み):
  - `WorldLoadHandler.onWorldLoadPost()` (malilib) が world 退出時に `saveAllConfigs()`、
    world 入場時に `loadAllConfigs()` を呼ぶ。
  - `InitializationHandler.onGameInitDone()` がゲーム起動完了時に `loadAllConfigs()` を呼ぶ。
  - `ConfigManager.{load,save}AllConfigs()` は登録された全 `IConfigHandler` の `load()/save()` を回す
    (map は modId キーだが iterate は values 全件なので、複数ハンドラを別キーで登録すれば全部呼ばれる)。

### 決定 (ユーザー選択 = 方針 A): 独立 JSON を独自管理

- 本家の `MaterialListBase.toJson()` に相乗りする案 (Mixin) は version 追従コストが高く却下。
- **同じ `IConfigHandler` ライフサイクルだけ流用し、自前ファイルに自前形式で書く**。
  - 保存先: `config/rawmats/craft_state.json` (export txt と同じ `config/rawmats/` 配下)。
  - キーは既存の store キー `class名 + getTitle()` のまま。これは **world 非依存** (schematic 名ベース) なので、
    world 固有ではなく **全 world 共通の単一ファイル**にした (同じ schematic は world を跨いで状態共有)。

### 実装 (ビルド OK / 実機未検証)

- `CraftTreeState`: `toJson()` / `fromJson(JsonObject)` を追加 (本家 `MaterialListBase` と同じ命名)。
  - `expanded` / `ignored` (`Set<Item>`) → 登録 ID 文字列の配列。`material_overrides`
    (`Map<TagKey<Item>, Holder<Item>>`) → `{ tag.location() : itemId }` の JSON object。
    `multiplier` / `sort_column` (enum name) / `sort_reverse` / `hide_available` はそのまま。
  - 復元時、未知 ID (mod 構成変更等で解決できないアイテム/タグ) は **スキップ** (`Identifier.tryParse` →
    `BuiltInRegistries.ITEM.getOptional` が empty なら捨てる)。`sort_column` は不正値なら TOTAL に fallback。
  - API (26.2 mojmap, sakura-ryoko fork): `ResourceLocation` は `net.minecraft.resources.Identifier` に改名。
    `Identifier.tryParse`, `TagKey.create(Registries.ITEM, id)`, `BuiltInRegistries.ITEM.{getKey,getOptional,
    wrapAsHolder}` を使用 (litematica `BlockUtils` / malilib の用例で裏取り)。
- `CraftTreeStore`: `saveToFile()` / `loadFromFile()` を追加。`{version:1, entries:{storeKey: state}}` を
  read/write。`save` は STORE が空なら何もしない (未使用ユーザーに空ファイルを作らない)。`load` は STORE を
  clear して置換。IO/parse 例外は warn ログで握り潰し (他ハンドラを巻き込まない)。
- `config/StatePersistence implements IConfigHandler`: `load()/save()` を上記に委譲する薄い adapter。
  `InitHandler` で **別 modId キー `rawmats_state`** で登録 (hotkey 用 `Configs` は modId `rawmats` のまま。
  ConfigManager の map は同一キー上書きなので別キーが必須)。

### 設計上の含意 / 既知の割り切り

- **保存タイミング = world 退出時 (+ hotkey config 画面を閉じた時)**。本家 `DataManager` と同じで、
  ハードクラッシュ / Alt-F4 では直近変更がロストする (本家も同様)。GUI クローズごとの即時保存は未実装。
- **load は game-init 時と world 入場時の 2 回**呼ばれるが、状態変更は in-world でしか起きず必ず world 退出時に
  save される → ディスクが常に最新なので load 置換は冪等で安全 (新しい in-memory 状態を上書きする経路は無い)。
- world 非依存キーのため、別 world でも同名 schematic は状態共有。これは仕様 (要らなければ後で world 別ファイルに分割可)。

### 未検証 (実機 runClient が必要)

- M,K で開いた状態 (展開/倍率/タグ選択/ソート/hide/ignore) が **MC 再起動後に復元**されるか。
- 未知 ID スキップ・破損 JSON 時の挙動 (warn ログのみで GUI は通常起動するか)。

---

## 2026-06-28 型 DAG cycle 対応 (stone↔cobblestone 双方向で行が消滅していた)

### 観察 (実機ログ)

`50_Array_Loose_Items_.litematic` で Expand all を押すと、display rows に stone も cobblestone も
出てこない。1段階ずつ expand すると `Furnace → Cobblestone → Stone` までは出るが、Furnace まで
collapse して再度展開すると最終的に消滅、というユーザー報告。

`logStructure()` ダンプを精読してわかったこと:

```
furnace x50 → cobblestone x400 (expandable=true, choosable=true) → stone x400 (leaf)
stone_bricks x6 → stone x8 (expandable=true) → cobblestone x8 (leaf)
```

- `cobblestone` の方が `choosable=true` で、`Cobblestone を作るレシピ` が `choosable` (タグ材料) として
  認識されている。つまり MC 26.2 vanilla の **stonecutter recipe で `stone → cobblestone` が追加されている**
  (`/recipe give @s *` で全 unlock 済み = recipe book に乗る)。ingredient はタグ材料で stone が候補から選ばれている。
- 一方 furnace の smelt は従来通り `cobblestone → stone`。
- 結果として型 DAG が `typeChildren[stone] = {cobblestone}`, `typeChildren[cobblestone] = {stone}` の
  相互参照になり、**cycle が生まれる**。

### 直接の原因

`CraftTree.getDisplayRows()` のグローバル集計は `topoExpandedTypes()` で各 type を親→子の topo 順に
1 度ずつ処理する設計だった。cycle 検出は `inStack` フラグで return するだけで、cycle 内 type に対し
後段の path から `addGross` された分はそのまま `pendingGross` に塩漬けになって、frontier にも積まれず
=表示行から消滅していた。

### 副次の原因 (こちらが先に当たりをつけて修正していた)

ユーザーの 1 段階 expand シナリオ (`Furnace → Cobblestone → Stone` の Stone を expand) では:

- Stone を `new MaterialListJsonBase(stone, n, prev=cobblestone, craftingOnly)` で再分解
- Stone の唯一のレシピ `smelt cobblestone → stone` の ingredient (cobblestone) が prev と一致して
  litematica の `checkIfLoop` (`MaterialListJsonBase.java:139-174`) が発火 → `materialsRemaining` に落ちる
- `CraftNode.fromBase` は Crafting/Stonecutter/Furnace のみ追って Remaining を見ない → children 空
- `getDisplayRows()` の元コードでは「children 空 → 何もせず continue」していたため、Stone が
  frontier にも pending にも積まれずに消滅

副次原因も cycle と同根 (型 DAG が cycle を持つこと自体は同じ。1 段階 expand と Expand all で見え方が違うだけ)。

### 修正 (`CraftTree.getDisplayRows()`)

1. **副次原因対応**: 再分解結果の `decomposed.children.isEmpty()` を検出して frontier に leaf として残す。
2. **直接原因対応**: topo loop で処理した type は `pendingGross[x] = 0` で「処理済み」マーク。
   loop 後に `pendingGross` を走査して残量 (cycle 経由で後段に積まれた分) を frontier に逃がす。
3. 両方とも `Reference.LOGGER.debug(...)` で診断ログ。発火した type と prev を出す。

### 診断ログ機構 (`-Drawmats.debug=true`)

Log4j2 のデフォルト閾値 INFO のままだと `.debug()` はどこにも出ない (runClient / 本番共に)。
ノイズを残さず開発時だけ debug を可視化したいので:

- `InitHandler` 起動時に `Boolean.getBoolean("rawmats.debug")` を見て、true なら
  `Configurator.setLevel(Reference.MOD_NAME, Level.DEBUG)` で rawmats logger だけ DEBUG に昇格 (root は触らない)。
  Log4j2 Core は MC が transitively 持っているので追加依存なしで使える。
- `build.gradle` の `runs.client.vmArgs` に `-Drawmats.debug=true` を追加 (runClient だけで debug 出る)。
- 本番ユーザーは何もしなければ INFO のまま、必要時のみ JVM 引数で昇格できる。

### 実機検証 (2026-06-28)

修正版で Expand all / 段階 expand 両方とも stone・cobblestone が残ることを確認 (ユーザー OK)。
DEBUG ログで `unprocessed pending residual minecraft:stone = N after topo (cycle)` が観測でき、
cycle が起きている型がログから即特定できる状態になった。

### 含意 / 残

- 型 DAG cycle 自体は他の MC バージョンや mod 追加レシピで他のペアにも発生しうる。今回の fix は
  ペア固有ではなく汎用 (`pendingGross` 残量を frontier に逃がす) のため、他 cycle にも自動的に効く。
- choosable な cobblestone は「stonecutter で stone から削ってもらう」という意味で、ユーザー的には
  「いやそんなレシピで作らねえよ」のケースが多いはず。今回は表示自体の消滅を直したのみで、
  「stonecutter 経路の choosable cobblestone を別素材に振り直す UX」は別件。

## 2026-07-04 Tab 長押しレシピ表示 + コンテナ画面からの M+K

### 変更 (ユーザー要望 2 点)

1. リスト内でホバー中の行に対し、Tab を押している間クラフトレシピを表示する。
2. インベントリ / 作業台などのコンテナ画面を開いたままでも M+K で開けるようにする。

### 実装 (ビルド OK / 実機未検証)

1. Tab 長押しレシピ表示
   - 新規 `gui/RecipeTooltip.java`。client recipe book (`RecipeBookUtils.getDisplayEntryFromRecipeBook`)
     から `RecipeDisplay` を引き、材料グリッド (shaped は width×height、shapeless は 3 列詰め、
     stonecutter/furnace は 1 マス) + `->` + 完成品 (個数は `renderItemDecorations`) を描く。
   - レシピ選択優先度はツリーの分解 (`CraftNode#chosenEntry`) と同じ crafting > stonecutter > furnace。
   - 染め直し系 (bed+dye 等) の回避: 出力自身が材料候補に含まれる entry をスキップして代替を使う。
     Litematica の `MaterialListJsonOverrides#overrideShouldSkipRecipe` (protected で呼べない) の汎用版。
   - タグ材料スロット (`SlotDisplay.TagSlotDisplay`) は、ユーザーが中クリックで置換済みなら
     その素材を固定表示、未選択なら候補を 1 秒周期で巡回表示 (vanilla recipe book と同じ見せ方)。
   - lookup は recipe book 全走査になるため `GuiCraftTree.recipeCache` (Map<Item, Optional<RecipeTooltip>>)
     に画面インスタンス単位でキャッシュ。
   - 表示箇所は `WidgetCraftTreeEntry.postRenderHovered`: `KeybindMulti.isKeyDown(KEY_TAB)` で
     集計ボックスと切替。レシピがある行は集計ボックス下部に灰色ヒント行
     (`rawmats.gui.label.recipe.hint`) を追加して発見性を確保。
   - 未開放レシピは recipe book に無いので表示されない (既存の展開と同じ制約、README Notes 通り)。

2. コンテナ画面からの M+K
   - `Hotkeys.OPEN_RAW_MATERIAL_LIST` の `KeybindSettings` を DEFAULT (Context.INGAME) から
     Context.ANY に変更 (他パラメータは DEFAULT と同一: PRESS / extra不可 / order敏感 / 非排他 / cancel)。
     malilib は `MixinKeyboard` → `InputEventHandler.onKeyInput` を Screen のキー処理より前に呼ぶので、
     cancel=true なら画面側に K が渡らない。
   - 発火範囲の絞りは `KeyCallbacks.onKeyAction` 側: `GuiUtils.getCurrentScreen()` が
     null (通常プレイ) か `AbstractContainerScreen` のときだけ処理。それ以外の GUI
     (チャット・設定画面・自 GUI 等) では false を返してキー入力を素通しする
     (`KeybindMulti.updateIsPressed` は callback が false なら cancel しない)。
   - 副作用対策: コンテナ画面から開くと `Minecraft.setScreen` が旧画面の `removed()` を先に呼び、
     作業グリッド内アイテムがインベントリへ返却される。CraftTree 構築 (在庫読み) はその前なので
     ずれる → `GuiCraftTree.initGui()` 冒頭で `tree.refreshAvailable()` を呼び直すようにした
     (`refreshAvailable` は rowsDirty も立てる)。展開クリック毎にも走るが在庫走査は軽量。

### 注意 / 未確認 (要実機)

- 仮説: クリエイティブ検索欄やレシピ本検索欄に高速で "m","k" を同時押し気味に打つと誤発火しうる
  (コンテナ画面内はテキストフォーカスを見ていない)。実害が出たらフォーカス判定を足す。
- Tab は malilib GUI のフォーカス移動キーでもある (検索バー展開時)。表示切替はキー状態の
  ポーリングのみで入力は消費しないため競合しない想定だが、実機で挙動確認すること。
- shapeless で材料 4 種の場合 3+1 の 2 段表示になる (vanilla の 2x2 表示とは異なるが割り切り)。

## 2026-07-04 Tab レシピ表示が出ない問題の調査 → glfwGetKey ポーリング不信 (26.2)

### 観察 (実機ログ、-Drawmats.debug 診断ログにて)

- DEBUG レベルのログは console (runClient stdout) には出ない。fabric dev の `run/logs/debug.log` にのみ出る。
  litematica が console に出す `[DEBUG]` は INFO レベルに文字列プレフィックスを付けているだけ。
- レシピ lookup は正常 (`recipe=true` が iron_ingot / gold_ingot / redstone / slime_ball 等で確認)。
- `postRenderHovered` も毎ホバーで呼ばれている (診断ログ 83 行)。
- 問題は Tab 検出: `KeybindMulti.isKeyDown(KEY_TAB)` (= `glfwGetKey`) が、Tab を押しっぱなしに
  している間 true/false を毎フレーム近く交互に返していた (例: sand で 1 秒間に 6 回反転)。
  一瞬 true になるフレームでは描画されるが、すぐ false に戻るため人間には「何も出ない」ように見える。

### 仮説 (原因)

MC 26.2 は入力層が KeyEvent ベースに刷新され IME preedit 対応も入っており
(`KeyboardHandler.submitPreeditEvent` 等)、GLFW のキー状態キャッシュがイベント配送と
同期しなくなっていると思われる。イベント (keyPressed/keyReleased) は正常なので、
ポーリングではなくイベント追跡が正解。malilib の `KeybindMulti.reCheckPressedKeys` も
同じ isKeyDown で PRESSED_KEYS を剪定しているため、押しっぱなし判定は malilib 全体で
race っぽい (M,K の発火は press イベント駆動なので実用上動く)。

### 修正

- `GuiCraftTree` に `tabHeld` フラグを追加。`onKeyTyped` (press/repeat) で立て、
  vanilla の Tab フォーカス移動を抑止するため消費。`keyReleased` override (`GuiEventListener`
  の default、26.2 の `Screen` は keyReleased を持たない) で解除。
  フォーカス喪失 (Alt+Tab) 時は GLFW が合成 release を配るので取りこぼしなし。
- `WidgetCraftTreeEntry` は `gui.isTabHeld()` を参照 (KeybindMulti.isKeyDown は使わない)。
- 診断ログ (`hover tooltip: item/tab/recipe`) は debug ゲートなので残置。

### Esc で元画面に戻す (同日、ユーザー要望)

- コンテナ画面から M+K で開いたとき `gui.setParent(screen)` を設定。malilib `GuiBase` の
  Esc → `closeGui(true)` → `mc.gui.setScreen(parent)` で元画面に戻る (Shift+Esc は親を出さず閉じる)。
- 画面切替時は `AbstractContainerScreen.removed()` → `menu.removed(player)` だけで close packet は
  送られない (bytecode 確認、`onClose()` のみ `closeContainer()` する)。JEI と同じ方式で、
  戻った後もコンテナは操作可能。実機 OK (ユーザー確認済み)。
- これに伴い「作業グリッドの返却で在庫がずれる」前提は変わった (グリッドの中身は返却されず
  menu が生きたまま)。`initGui()` の `refreshAvailable()` は在庫の取り直しとして引き続き有効。

### 訂正 (同日): イベント駆動でも点滅 → release+press ペアが「イベント列そのもの」に来ている

keyPressed/keyReleased 追跡に替えても診断ログは同じ true/false 交互パターンだった
(sand で ~200ms 間隔の反転)。つまり glfwGetKey が壊れているのではなく、
押しっぱなし中のキーリピートが「release イベント + press イベントのペア」として
Minecraft のイベント列に配送されている。

環境調査:
- PowerToys KeyboardManagerEngine 常駐を確認。ただし remap は VK243/244 (半角/全角) → VK49 のみで
  Tab は remap されていない。断定はできないが、低レベルフック通過の副作用の可能性は残る。
- より有力なのは MC 26.2 + LWJGL 3.4.1-snapshot の IME 対応入力パス (KeyboardHandler に
  preedit 系 API が追加されている) が日本語 IME 環境でリピートを release+press に変換している説。
- M+K (hotkey) が問題なく動くのは、リピート開始前 (~500ms) の短い操作で完結するため。
- キーリピート設定: KeyboardDelay=1 (~500ms), KeyboardSpeed=31 (最速 ~33ms 間隔)。

### 修正 (debounce)

`GuiCraftTree.isTabHeld()`: release イベントを即時反映せず、release 後 300ms
(`TAB_RELEASE_GRACE_MS`、観測反転間隔 ~200ms を覆う) 以内に次の press (リピート) が
来なければ「離した」と判定。押下側は press/repeat イベントで即 on。副作用は
「離してから最大 300ms レシピ表示が残る」のみ。Tab の press/release イベント時刻を
debug ログに出すようにした (パターンの実測用、`tab press/release event at <ms>`)。

### 訂正 (同日): 「phantom release+press ペア」説は誤り → ポーリング実装に差し戻し

- Tab レシピ表示は debounce 版で表示されることをユーザーが確認。ただし表示されなかった
  本当の理由は入力ではなく「テスト対象の行」だった:
  - 展開後の原材料リストはほとんどが「レシピを持たない生素材」(bone, soul_sand, sand, string 等) で、
    ユーザーがマウスを止めて確認していたのは全部この recipe=false の行だった (仕様どおり集計ボックスのまま)。
  - recipe=true の行 (gold_ingot 等) は Tab を押しながら高速通過しただけで視認できていなかった。
- イベント実測 (tab press/release ログ) で入力列はクリーンと判明: release → 67〜168ms 後の press は
  高速な押し直し、その後 500ms の初回リピート遅延 → 33ms 間隔のリピート、という人間の操作そのもの。
  診断ログの true/false 交互パターンは Tab「連打」を正しく拾っていただけ。
  glfwGetKey ポーリング不信説・IME/LWJGL 入力パス説はいずれも根拠を失ったため撤回。
- ユーザー指示により最初の実装に差し戻し: `WidgetCraftTreeEntry` で
  `KeybindMulti.isKeyDown(KEY_TAB)` をポーリング。GuiCraftTree の tabHeld 追跡・
  keyReleased override・debounce・診断ログ (hover tooltip / tab press/release) は全て削除。
- 残った教訓: DEBUG レベルのログは console に出ず `run/logs/debug.log` にのみ出る (診断時はそちらを見る)。

## 2026-07-05 ignore の需要伝播カット (#7) + ユニットテスト基盤

### 問題 (issue #7)

最終形態に近いアイテム (oak trapdoor / sign / fence gate 等) を ignore しても、
expand all 後の原料 (oak log 等) の必要数に反映されなかった。原因は
`getDisplayRows()` が `state.ignored` を frontier 行の出力段でしか見ておらず、
需要集計 (seed → pending → 分解 → frontier) には ignore が一切効いていなかったこと。
`expandAll()` は ignore 済みの型も展開対象に含めるため、ignore した型の需要は
子へ分解されて原料行に合算され、その型自身の行だけが隠れていた。

### 修正

- ignore の意味を「行を隠す」から「その素材と、そこから派生する原料需要を集計から除外する」に変更。
  `addGross()` の入口で `state.ignored` に含まれる型の需要を捨てる。需要の流入
  (root seed / 分解の子) はすべてここを通るので 1 箇所で全経路に効く。cycle 残渣の
  frontier 逃がしは pendingGross 由来 (= addGross 通過済み) なので追加の遮断は不要。
- 行出力段の既存フィルタは保険として残した。
- 副次効果として、中間素材 (planks 等) を ignore するとその先 (log) への伝播も止まる。
  「その素材は別途用意するので原料は不要」という解釈で一貫させた。
  ignore された型は pending に入らないため、在庫の引き当ても発生しない。

### ユニットテスト基盤 (新規)

- `build.gradle` に `testImplementation "net.fabricmc:fabric-loader-junit"` + `useJUnitPlatform()`。
  fabric-loader-junit 経由で Minecraft クラスと registry bootstrap
  (`SharedConstants.tryDetectVersion()` → `Bootstrap.bootStrap()` を `@BeforeAll` で) が使える。
- 分解は litematica の `MaterialListJsonBase` 直呼びだったが、これは malilib の
  RecipeBookUtils 経由で「実行中クライアントの recipe book」に依存しユニットテスト不能。
  そこで分解関数を `CraftTree.Decomposer` (item, count, parent, depth → CraftNode) として抽出し、
  production は従来実装 (`decomposeWithRecipeBook`)、テストはバニラ相当の fake レシピテーブルを注入。
- `rebuild(MaterialListBase)` を「直材需要リスト (`Demand`) の抽出」+「`rebuildFromDemands`」に分離。
  テストは `new CraftTree(List<Demand>, state, decomposer)` で source (litematica) 非依存に構築できる。
  倍率は `rebuildFromDemands` 内で適用し、source 無しでも `setMultiplier` が機能する。
- `refreshAvailable()` は `Minecraft.getInstance()` が null (テスト環境) でも落ちないようガード。
  在庫はテストからは package-private の `setAvailableForTest` で注入。
- テスト: `CraftTreeIgnoreTest` (6 件)。バグ再現 (全消費元 ignore → 原料行ゼロ)、部分 ignore、
  frontier ignore の回帰、中間素材 ignore の伝播停止、倍率+在庫ネット、ignore 型在庫の非消費。

## 2026-11-03 タグ材料の「任意」行と、展開先での種類変更 (#15、0.5.0)

### 発端 (issue #15)

1. 木の種類の切り替えが「樹皮を剥いだ版 / 木」の間だけで、oak ↔ spruce にできない。
2. ピストン、チェスト、棒、音符ブロックなど、木の種類を問わないレシピが種類を問わない扱いにならない。

### 観察 (26.3 実機、Expand all のダンプ)

- チェストの材料は `#planks` (候補 13)。板材の行は中クリックで 13 種から選べる (既存機能)。
- 板材を展開した原木の行 (crimson_stem) は `#crimson_stems` (候補 4) 由来。中クリックでは同じ種類の 4 つしか出ない。
  → issue 1 はこれ。板材の段階なら種類を選べるが、展開後は選び直せない。
- spruce_trapdoor → spruce_planks (具体指定) → spruce_log (`#spruce_logs`)。レシピが種類を固定しているので、他種が出ないのは正しい。
- 種類を決めない数え方は無い。Litematica が具体アイテムに決めた結果 (ユーザーが選び直さなければ 1 種) で集計している。
  → issue 2 はこれ。
- バニラの en_us.json に `tag.item.*` の翻訳は無い。

### 決定 (ユーザーと合意)

- タグ材料は、標準で「任意」の行にする (例: `#planks` ×840)。アイコンはタグ内のアイテムを 1 秒ごとに巡回表示
  (バニラのレシピ本と同じ見せ方) して、どれでもよいことを示す。
- 中クリックのメニューで「任意」と具体アイテムを行き来できる。以前に具体アイテムを選んで保存した設定はそのまま具体で読む。
- 展開した先の原木の行でも、元のタグ (`#planks`) の選択を変えられる (issue 1)。
- タグ名の `minecraft:` を省略する設定を追加 (標準 true、`#minecraft:logs` → `#logs`)。
- バージョンは 0.5.0。

### 設計

- 集計の単位を `Item` から `Mat` (具体アイテム `Mat.Of` / 任意 `Mat.AnyOf`) に広げる。
  `AnyOf` はメンバー集合で同一性を判定し、元が名前付きタグならそのキーも持つ。ID は `#ns:path` (タグ) / `{a,b,...}` (集合)。
- `CraftNode` は分解結果をそのまま持ち、タグ由来の子に `choice` (受け付ける集合) を付ける。
  「任意」化と override 適用は `CraftTree` 側 (`applyChoices`) に移す (fromBase から override 処理を外す)。
  これでテストの fake decomposer も同じ経路を通る。
- 「任意」の分解 (`decomposeAny`): メンバー全員を同じ大きめの個数で分解して形を比べ、
  代表 (Litematica が選んだアイテム) と形 (材料数・各材料の個数) がそろうメンバーをまとめる。
  材料がメンバーごとに違うスロットは、和集合の `AnyOf` にする (一致する item タグがあればそれを名前に使う)。
  形がそろわないメンバー (竹の板材など) は除外として記録し、ツールチップに出す。
  和集合が自分自身のメンバーを含む場合 (循環) は「任意」の分解をやめ、代表アイテムで分解する。
  形の比較は種類に依存しない一般の処理で、木材専用の分岐は書かない。
- 在庫: 具体アイテムの行を先に引き当て、余りを「任意」の行へ (狭い集合から)。
  展開した型の分解パスでも同じく、型の処理順で具体アイテムを先にする。
- 展開先での種類変更: 具体指定されたタグスロットの子 (例: crimson_planks → crimson_stem) に、
  親のタグ (`#planks`) と子のスロット位置を `upstream` として記録する。原木の行のメニューに、
  親タグの各メンバーに対応する原木 (分解形の比較で得たもの) と「任意」を並べ、選ぶと親タグの選択を変える。
- 状態 (`CraftTreeState`) の expanded / ignored / override は Mat の ID 文字列で保存する。
  既存のファイル (アイテム ID、override のキーは `#` 無しのタグ ID) はそのまま読める。

### 実装中に分かったこと / 逸脱 (2026-11-03)

- 【観察】原木の段階のタグ (`#spruce_logs` = spruce の原木 / 樹皮を剥いだ原木 等) も既定で「任意」になる。
  タグ材料はすべて既定で「任意」という決定どおりの結果。spruce_trapdoor 由来の原木は `#spruce_logs` の行になる。
- 【逸脱】展開先から親タグの選択を変える印 (`upstream`) は、ツリー構築時 (resolveChild) だけでなく分解パスの再分解でも
  付ける必要があった。展開した型は分解パスで作り直すので、構築時の印が届かない。
- 【観察】26.3 では `SlotDisplay.TagSlotDisplay#tag()` が TagKey ではなく HolderSet を返す。以前のコードは
  `Map.get(Object)` に渡していたのでコンパイルは通り、26.3 では Tab のレシピ表示に選択した素材が反映されていなかったと思われる。
  両方の型を受けるようにした。
- 【観察】実機の `#planks` の分解形: まとめたのは 12 種 (poplar を含む)、`#logs` に一致、除外は bamboo_planks のみ。
  `#stone_crafting_materials` は cobblestone / cobbled_deepslate がまとまり、blackstone が除外された。
- 実機確認の途中で、26.3 のマウスボタンの番号の変化 (docs/notes/multi-version.md) と、集計の「Format Error」
  (malilib の WidgetLabel が行をもう一度書式化する) が見つかり、別コミットで直した。
- 状態の確認用に、デバッグ起動 (`-Drawmats.debug`) のときだけ出る Dump state ボタン (状態を丸ごとファイルへ) と、
  行のクリック / 展開 / 畳みの記録を追加した。ログファイルは INFO 以上なので、debug ではなく info で出している。
- 【未決】種類を指定する需要 (oak の階段の oak 板材など) があるとき、「任意」の需要をその種類に寄せるかは保留。今は別の行。
