# マルチバージョン対応の調査と構成

時系列で追記する。観察 (事実) と仮説 (推論) は分けて書く。

## 2026-07-06 調査

### 対応可能なバージョンの下限

観察:
- litematica (sakura-ryoko) の維持ブランチ: 26.2, LTS/26.1, LTS/1.21.11, LTS/1.21.10, LTS/1.21.8, LTS/1.21.5, LTS/1.21.4, LTS/1.21.3, LTS/1.21, LTS/1.20.x
- rawmats が土台にしている litematica の `materials/json` パッケージ (MaterialListJsonBase 等) が存在するのは **1.21.8 ブランチ以降のみ**。1.21.5 以下には無い。
  - LTS/1.21.8 への登場は 2025-06〜07 (コミット履歴)。craftingOnly 引数付きの現行世代 API。
- malilib の `mixin.recipe.IMixinIngredient` も 1.21.8 以降に存在。
- 2026-07-06 に litematica が LTS 全系列 (1.21〜1.21.10) を Modrinth に一斉リリースしたが、1.21.5 以下に materials/json は含まれない (ブランチ HEAD に無い)。

結論: **litematica の全対応バージョンとの完全一致は不可能**。理論上の下限は 1.21.8。

### malilib API の世代の切れ目

観察:
- LTS/1.21.11, LTS/26.1, 26.2 ブランチ: mojmap ソース (Minecraft/GuiGraphics/Font)、`render.GuiContext` あり、`GuiBase.onMouseClicked(MouseButtonEvent, boolean)`。同世代 API。
- LTS/1.21.10 以前: Yarn ソース (MinecraftClient/DrawContext)、`render.GuiContext` 無し (`RenderContext`)、入力コールバックが int / `Click` ベース。旧世代 API。
  - mojmap でビルドすれば MC クラス名は loom が remap して吸収できるが、malilib 自身の API 形状 (GuiContext 不在等) は吸収できず、GUI 層全面に条件分岐が必要になる。

結論: 1.21.10 / 1.21.8 対応は可能だが GUI 層の大改修。ユーザー判断で **対象は 1.21.11 / 26.1.x / 26.2 の3系列に確定** (2026-07-06)。

### ビルド構成 (採用したもの)

- Stonecutter 0.9.6 (公式 fabric テンプレート準拠、Kotlin DSL へ移行)
  - offers-hud (stonecutter 0.8.2 + architectury loom の自前分岐) も参照したが、0.9.6 + `dev.kikugie.loom-back-compat` 0.3 が unobfuscated (26.1+) / obfuscated (1.21.x) の loom 差をプラグイン側で吸収するため、公式テンプレートに沿った。
  - `loomx.applyMojangMappings()` が obfuscated バージョンにだけ mojmap を適用。
  - `mod*` 依存 (modImplementation) は 26.1+ では plain 構成に自動変換される。
- バージョン別プロパティは `stonecutter.properties.toml` (0.9 の集中管理方式)。gradle.properties の mod_version 等はここへ移動。
- fabric.mod.json の depends は processResources で `${mc_compat}` 等を expand。
- 依存 floor はコンパイルに使ったバージョン (それ未満での API 存在は未検証のため):
  - 1.21.11: malilib 0.27.16 / litematica 0.26.11
  - 26.1.x: malilib 0.28.9 / litematica 0.27.9 (MC 26.1.2 でコンパイル、互換範囲 ~26.1)
  - 26.2: malilib 0.29.3 / litematica 0.28.3 (宣言 floor は従来どおり 0.29.0/0.28.0)

### コード側の差分 (1.21.11 vs 26.x)

コンパイルで実測した差分は4箇所のみ:
1. `ItemStack.typeHolder()` (26.x) ↔ `getItemHolder()` (1.21.11) — CraftTree
2. `fi.dy.masa.malilib.util.data.ItemType` (26.x) ↔ `fi.dy.masa.malilib.util.ItemType` (1.21.11) — import 分岐。1.21.11 にも data.ItemType は存在するが、litematica 1.21.11 の `MaterialListUtils.getInventoryItemCounts` が旧 ItemType を返すため合わせるしかない (deprecation 警告は litematica 側由来で無害)
3,4. `GuiContext.renderTooltip(Font, List<Component>, int, int)` は malilib 0.29 世代のヘルパーで 1.21.11 (0.27.16) に無い → 素の `setComponentTooltipForNextFrame` を使用 (GuiCraftDag / GuiCraftGraph)
   - 【訂正 2026-07-06】GuiCraftGraph (ツリー表示) はこの後削除したため、現存する分岐は GuiCraftDag の1箇所

26.1.x (malilib 0.28.9 / litematica 0.27.9) は**コード変更ゼロ**でコンパイル可。

### 検証状況

- `gradlew build` で3ターゲットとも jar 生成 + fabric-loader-junit テスト (CraftTreeIgnoreTest) 通過。
  - rawmats-fabric-1.21.11-0.4.0.jar / rawmats-fabric-26.1.2-0.4.0.jar / rawmats-fabric-26.2-0.4.0.jar
- 26.2.x の runClient: 起動確認済み (rawmats ロード、エラーなし、タイトル画面到達)
- 1.21.11 の runClient: 起動確認済み (rawmats ロード、mixin エラーなし、タイトル画面到達)。ただし GUI の in-game 動作 (M+K での各ビュー表示・操作) は未検証
- 26.1 の実機起動は未検証 (仮説: コード差分ゼロでコンパイルが通っているため動く可能性が高い)

### 運用メモ

- 開発時のアクティブバージョンは `stonecutter.gradle.kts` の `stonecutter active "26.2.x"`。切替は `gradlew "Set active project to 1.21.11"` (IDE 補完用にソースが書き換わる)。**アクティブ切替のままコミットしない** (vcsVersion = 26.2.x に戻す: `gradlew "Reset active project"`)。
- タスクはサブプロジェクト付きで叩く: `gradlew :26.2.x:runClient`, `gradlew :1.21.11:test` 等。`gradlew build` は全バージョン。
- バージョン追加時は settings.gradle.kts の `version(...)` と stonecutter.properties.toml のテーブルを足す。

## 2026-07-06 追記: litematica セキュリティ advisory と依存 floor

観察:
- litematica に脆弱性の advisory が出た。実態は 0.28.2 以前の Servux 連携によるスキマティックの
  ファイル受信処理にパストラバーサル経由の**任意ファイル書き込み**が成立する経路 (直接の RCE ではない。
  advisory の当初文言は「コマンドを実行できる」だったが、任意ファイル書き込みが間接的に
  コード実行へつながり得るための表現と思われる)。修正版:
  - 1.21〜1.21.1: 0.19.61 / 1.21.2〜3: 0.20.9 / 1.21.4: 0.21.7 / 1.21.5: 0.22.5
  - 1.21.6〜8: 0.23.7 / 1.21.9〜10: 0.24.8 / 1.21.11: 0.26.11 / 26.1.x: 0.27.9 / 26.2: 0.28.3
- この日の LTS 全系列一斉リリース (上記の調査で観察したもの) は、この advisory の修正配布と一致する。
  当時「対応バージョンが急に増えた」ように見えたのはこれが理由 (当時は理由不明のまま記録していた → 本項で訂正・補足)。

対応:
- rawmats の litematica floor をセキュリティ下限を兼ねる値に設定。1.21.11 (>=0.26.11) と 26.1.x (>=0.27.9) は
  既に修正版が floor だったため変更なし。26.2 のみ >=0.28.0 → **>=0.28.3** に引き上げ。
- stonecutter.properties.toml に「これ未満に floor を下げない」旨のコメントを追加。

## 2026-11-03 26.3 対応

### 依存の公開状況

観察:
- MC 26.3 はリリース済み (latest release = 26.3、snapshot は 26.4 系)。26.3.x のパッチ版はまだ無い。
- masa maven (sakura-ryoko): malilib-fabric-26.3 0.30.0〜0.30.2、litematica-fabric-26.3 0.29.0〜0.29.1 (2026-09-27 公開)。
  - litematica 0.29.1 の depends: `minecraft >=26.3 <26.4`, `malilib >=0.30.2- <0.31.0-`。`materials/json` パッケージあり。
- 以前に対応しようとしたときは周辺 mod が出ていなかった (ユーザー談)。今回はそろっていた。

### masa maven が古い版を消している (既存系列のビルド破損)

観察:
- 既存系列のコンパイル依存のうち malilib 0.28.9 以外 (malilib 0.27.16 / 0.29.3、litematica 0.26.11 / 0.27.9 / 0.28.3) が 404。
  maven-metadata.xml には系列ごとに最新 3〜4 版しか載っていない:
  - 1.21.11: malilib 0.27.17〜0.27.20 / litematica 0.26.14〜0.26.16
  - 26.1.2: malilib 0.28.9〜0.28.12 / litematica 0.27.12〜0.27.14
  - 26.2: malilib 0.29.3〜0.29.6 / litematica 0.28.6〜0.28.8
- そのため 26.3 の追加とは関係なく、クリーン環境 (loom キャッシュ無し) の `gradlew build` は configure 段階で失敗する状態だった。

対応:
- 既存 3 系列の deps.* を、残っている最新版 (上記の末尾) に上げた。
- 【逸脱】これまでの方針は「floor = コンパイルに使った版」だったが、宣言 floor (*_compat) は据え置いた。理由:
  - rawmats 側のソースはこの変更 (KeyCodes の import 分岐以外) で変わっておらず、同じソースが旧 floor 版でもコンパイルできていた。
    よって旧 floor 版にも、rawmats が使う API は存在すると考えられる (仮説だが、根拠は前回のコンパイル実績)。
  - floor を上げると、旧版の litematica を使っているユーザーを理由なく弾くことになる。
  - 26.2 は以前からコンパイル 0.29.3 / floor 0.29.0 で、すでに floor とコンパイル版がずれていた。
- 今後も版が消えるとビルドが壊れる。消えたら、そのとき残っている版に deps.* を上げる。

### コード差分 (26.3)

観察:
- malilib 0.30 で `fi.dy.masa.malilib.util.KeyCodes` が `fi.dy.masa.malilib.util.input.KeyCodes` に移動 (旧パッケージには無い)。
  KEY_TAB / KEY_ESCAPE は移動先にある。`KeybindMulti.isKeyDown(int)` は従来どおり。
- コンパイルエラーはこれだけ。GuiCraftDag / GuiCraftTree / WidgetCraftTreeEntry の import を `//? if >=26.3` で分岐した。

構成変更:
- settings.gradle.kts に `version("26.3.x", "26.3")`、vcsVersion と active を 26.3.x に変更。
  - vcsVersion の系列は `src` をそのままコンパイルするため、新しい分岐を足す場合は vcsVersion 側の分岐を有効にしておく必要がある
    (最初は vcsVersion を 26.2.x のまま 26.3 の分岐を有効にして、26.2.x のコンパイルが壊れた)。
- publish-release.yml の matrix に 26.3.x (game_versions: 26.3) を追加。

### 検証状況

- `gradlew build` で 4 系列とも jar 生成 + テスト通過 (rawmats-fabric-{1.21.11,26.1.2,26.2,26.3}-0.4.1.jar)。
- 26.3.x の runClient: rawmats 0.4.1 ロード、タイトル画面到達。rawmats/mixin 由来のエラーなし
  (ログの ERROR は oshi の perf counter と、共有 run/options.txt の `scancode.115` キー設定で、rawmats とは無関係)。
- 未検証: 26.3 のワールド内での GUI 操作 (M+K の各ビュー表示・Tab/Esc の挙動)。deps を上げた既存 3 系列の実機起動。
- 【追記 2026-11-03】26.3 のワールド内 GUI はユーザーが手動でテストし、問題なしとの報告 (`/recipe give @s *` でレシピを全開放した状態)。
  同じ回の runClient ログに rawmats 由来の例外は出ていない。既存 3 系列の実機起動は引き続き未検証。

## 2026-11-03 0.4.2 リリース前の起動確認

観察 (mod.version 0.4.2、各系列の runClient でタイトル画面到達まで。ワールドには入っていない):
- 1.21.11: litematica 0.26.16 / malilib 0.27.20 / fabricloader 0.19.5 (Java 21)。rawmats 0.4.2 ロード、ERROR なし。
- 26.1.x: litematica 0.27.14 / malilib 0.28.12 / MC 26.1.2。rawmats 0.4.2 ロード、ERROR なし。
- 26.2.x: litematica 0.28.8 / malilib 0.29.6。rawmats 0.4.2 ロード。例外は oshi (perf counter / OSProcess null) のみで、26.3 と同じく rawmats とは無関係。
- これで「deps を上げた既存 3 系列の実機起動」の未検証項目は、起動 (タイトル画面) までは確認済み。ワールド内の GUI 操作は既存 3 系列では未確認。
- 宣言 floor の版 (例: litematica 0.26.11) での起動は確認していない (maven から消えているため dev 環境では試せない)。

## 2026-11-03 依存の取得先を Modrinth maven に移行

背景: masa maven が古い版を消すため、固定した deps がそのうち 404 になる (上の項)。他 mod の事例を別セッションで調べた。
仕組みで対処していたのは、Modrinth maven に寄せる方法 (MagicLib / MasaGadget / conflux-map) と、
masa maven の最新版へ一括で上げるスクリプトを置く方法 (tweakermore) だった。前者を採用。

観察:
- Modrinth maven では `maven.modrinth:<slug>:<version_number>` が MC 系列ごとの jar にリダイレクトされる
  (例: malilib:0.27.16 → malilib-fabric-1.21.11-0.27.16.jar、litematica:0.29.1 → litematica-fabric-26.3-0.29.1.jar)。
  0.26 以降の版番号は系列をまたいで重複しない (調査セッションの確認)。
- Modrinth には floor の版が全部ある (malilib 0.27.16 / 0.28.9 / 0.29.2 / 0.30.2、litematica 0.26.11 / 0.27.9 / 0.28.3 / 0.29.1)。
  一方で litematica 0.26.10 (advisory より前の版) は 404。Modrinth でも脆弱版は消えている。
- floor の litematica が要求する malilib: 0.26.11 → >=0.27.14、0.27.9 → >=0.28.8、0.28.3 → >=0.29.2、0.29.1 → >=0.30.2。
  26.2 の malilib floor `>=0.29.0` は litematica 0.28.3 の要求 (>=0.29.2) より低く、実質的に意味が無かった。
- Modrinth の POM には依存が書かれていない。masa maven の POM は fabric-api-base / fabric-networking-api-v1 /
  fabric-resource-loader-v1 / conditional-mixin / fabric-loader を推移的依存に書いていた。
  - 1.21.11 (remap する系列) では fabric-loader-junit のテストが「fabric-networking-api-v1 が無い」で起動しなかった。
    loom の remap 後の malilib jar では、`META-INF/jars/` に同梱 jar は残っているが、fabric.mod.json から `jars` が消えている。
    そのため同梱分が dev 環境で読まれない。
  - 26.x (remap しない系列) は同梱分がそのまま読まれる (ロード一覧で malilib / litematica の下に `\--` 付きで出る)。
  - fabric-loader-junit は同梱 jar を `versions/<v>/.fabric/processedMods/` に展開する → .gitignore に追加。
- fabricloader は 0.19.5 → 0.19.3 になった。masa の POM が runtime に 0.19.5 を入れていたのが無くなり、deps.fabric_loader の値が使われるため。

対応:
- build.gradle.kts: masa maven と jitpack を外し、Modrinth maven を `maven.modrinth` グループ限定で追加。
  FallenBreath maven は conditional-mixin 用に残す。
- 1.21.11 だけ `deps.dev_runtime` で、malilib 0.27.16 の同梱 jar と同じ版の 4 つを modLocalRuntime に入れる (dev 実行時のみ)。
- deps.* を宣言 floor と同じ版に戻した (2026-11-03 の前の項の「逸脱」を解消)。
  26.2 は malilib floor を >=0.29.2 に上げ、deps.malilib も 0.29.2。README の表も直した。

検証:
- `gradlew build`: 4 系列とも jar 生成、テスト 6 件通過。
- runClient (タイトル画面まで。ワールドには入っていない) で 4 系列とも rawmats 0.4.2 がロードされ、エラーなし。
  conditional-mixin と fabric-api のモジュールもロードされている:
  - 1.21.11: litematica 0.26.11 / malilib 0.27.16。fabric-api モジュールと conditional-mixin はトップレベルでロード (dev_runtime 由来)。
  - 26.1.x: litematica 0.27.9 / malilib 0.28.9、26.2.x: 0.28.3 / 0.29.2、26.3.x: 0.29.1 / 0.30.2。いずれも同梱分としてロード。

残る懸念 (仮説):
- 今後また advisory が出ると、Modrinth でも floor 未満の版がまとめて消える可能性がある。floor を修正版に合わせて上げる運用なら影響は小さいと思われる。
- CI の actions/cache は restore-keys で前方一致の復元をするので、キャッシュがある間は依存が消えても気づけない。

## 2026-11-03 26.3 でマウスボタンの番号が変わっていた (クリック操作の不具合)

観察:
- 26.3 の実機で、行の左クリックで展開できない、左クリックで畳まれる、右クリックが効かない、という症状が出た。
  デバッグ用のクリックログでは、左 = 1、中 = 2、右 = 3 で届いていた。
- `com.mojang.blaze3d.platform.InputConstants` の値:
  - 26.3: MOUSE_BUTTON_LEFT = 1 / MIDDLE = 2 / RIGHT = 3 (SDL の並びと思われる)
  - 1.21.11 / 26.1.2 / 26.2: LEFT = 0 / RIGHT = 1 / MIDDLE = 2 (GLFW の並び)
  - `MouseButtonEvent.input()` はどの版も生のボタン番号を返す。
- rawmats は `click.input() == 0/1/2` と数値を直書きしていたため、26.3 ではずれていた。
  2026-11-03 の 26.3 対応 (0.4.1 の手動テスト) では見落としていた (「大丈夫」との報告は主に表示の確認だったと思われる)。

対応:
- 直書きを `InputConstants.MOUSE_BUTTON_LEFT/RIGHT/MIDDLE` に置き換えた (GuiCraftDag / WidgetCraftTreeEntry)。
  定数はコンパイル時に埋め込まれるので、版ごとのビルドで正しい値になる。
- この不具合は 0.4.2 (未リリース) の 26.3 版にも入っている。
