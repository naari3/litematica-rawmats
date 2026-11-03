package io.github.naari3.rawmats.gui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import org.joml.Matrix3x2fStack;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.Holder;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.ButtonOnOff;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.hotkeys.KeybindMulti;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;
//? if >=26.3 {
import fi.dy.masa.malilib.util.input.KeyCodes;
//?} else {
/*import fi.dy.masa.malilib.util.KeyCodes;
*///?}
import fi.dy.masa.malilib.util.StringUtils;

import io.github.naari3.rawmats.materials.CraftNode;
import io.github.naari3.rawmats.materials.CraftTree;

/**
 * クラフトフロー: 手順ビュー ({@link CraftSteps}) と同じ「アイテム種別でまとめた DAG」を、
 * ツリー表示と同じパン/ズームのキャンバスに左→右で描く。
 *
 * ツリー表示との違い: 共有素材 (stick 等) が 1 ノードに集まり、辺が合流する。
 * その代わり木ではないので、レイアウトは簡易 Sugiyama:
 * - 列 = 最長路レイヤリング (素材 = 左端、成果物は全材料より右)。
 * - 列内の並びは barycenter 法 (隣接ノードの平均 y に寄せて交差を減らす) を数回。
 * - 辺は斜めの直線 (回転行列で 1px 矩形を回す)。
 *
 * 展開状態・除外・タグ選択はフラットリストと共有 ({@link CraftTreeState})。
 * 操作もリストと同じ: 左クリック = 展開、右クリック = 折りたたみ (Shift+右 = 除外)、
 * 中クリック = タグ素材の変更、Tab 長押し = レシピ表示。
 * 数値はリストと同じグローバル在庫ネット ({@link CraftTree#computeNetPass})。
 */
public class GuiCraftDag extends GuiBase
{
    private static final int ROW_PITCH = 26;
    private static final int ROW_H = 20;
    private static final int H_GAP = 32;

    private static final int COLOR_EDGE = 0x80A0A0A0;
    /** ホバー中ノードに繋がる辺 (少し明るく) / それ以外 (少し薄く)。控えめな差に留める。 */
    private static final int COLOR_EDGE_HI = 0xC0D0D0D0;
    private static final int COLOR_EDGE_DIM = 0x38909090;
    /** ホバー時に経路外ノードへ重ねる暗幕 (アイコンごと少し沈ませる)。 */
    private static final int COLOR_NODE_DIM = 0x88101010;

    /** ハイライト発動までのホバー継続時間。マウス通過だけでは光らせない (フラッシュ対策)。 */
    private static final long HIGHLIGHT_DELAY_MS = 100;
    /** ハイライト強度のイージング速度 (大きいほど速い。12 ≈ 80ms で 63%)。 */
    private static final float HIGHLIGHT_SPEED = 12f;
    private static final int COLOR_MARKER = 0xFFFFC800;
    private static final int COLOR_COVERED = 0xFF60E060;

    /** バニラのチェックマークスプライト (白 9x8)。充足ノードに緑 tint で描く。 */
    private static final Identifier CHECKMARK_SPRITE = Identifier.withDefaultNamespace("icon/checkmark");
    private static final int COLOR_BOX_BG = 0xE0101010;
    private static final int COLOR_BOX_BORDER = 0xFFC0C0C0;
    private static final int COLOR_BOX_BORDER_ROOT = 0xFFE0B040;
    private static final int COLOR_LEAF_BG = 0x4060B0FF;

    private final CraftTree tree;
    private final List<DNode> nodes = new ArrayList<>();
    /** タグ素材変更が可能な型 -> 代表ノード (CraftSteps の集計から)。 */
    private Map<Item, CraftNode> choosable = Map.of();
    /** Tab 長押しレシピ表示の lookup キャッシュ (GuiCraftTree と同じ方式)。 */
    private final Map<Item, Optional<RecipeTooltip>> recipeCache = new HashMap<>();
    @Nullable private WidgetCraftMenu popup;
    /** 「除外中」ボタンの x (除外一覧メニューのアンカー)。 */
    private int ignoredBtnX;

    // ホバーハイライトの遷移状態 (フラッシュ対策: 遅延発動 + フェード)。
    @Nullable private DNode hoverNode;
    private long hoverSince;
    private long lastFrameMs;
    private float highlightStrength;
    @Nullable private Set<DNode> hlForward;
    @Nullable private Set<DNode> hlBackward;

    private double offX, offY;
    private float scale = 1.0f;
    private boolean dragging;
    private int contentMinX, contentMaxX, contentMinY, contentMaxY;

    public GuiCraftDag(CraftTree tree)
    {
        this.tree = tree;
        this.title = StringUtils.translate("rawmats.gui.title.craft_dag");
        this.useTitleHierarchy = false;
    }

    @Override
    public void initGui()
    {
        super.initGui();
        this.rebuildButtons();
        this.popup = null;
        this.build();
        this.offX = -((this.contentMinX + this.contentMaxX) / 2.0);
        this.offY = -((this.contentMinY + this.contentMaxY) / 2.0);
    }

    /** 上部ボタン列。左に展開/畳み、右上に除外系 (除外の有無で増減するので状態変化時に作り直す)。 */
    private void rebuildButtons()
    {
        this.clearButtons();

        int x = 12;
        ButtonGeneric collapseAll = new ButtonGeneric(x, 24, -1, 20, StringUtils.translate("rawmats.gui.button.collapse_all"));
        x += this.addButton(collapseAll, new ActionListener(this, this.tree::collapseAll)).getWidth() + 2;
        ButtonGeneric expandAll = new ButtonGeneric(x, 24, -1, 20, StringUtils.translate("rawmats.gui.button.expand_all"));
        x += this.addButton(expandAll, new ActionListener(this, this.tree::expandAll)).getWidth() + 2;

        // リストと同じトグル (状態共有)。切替時はラベル更新のためボタンを作り直す。
        ButtonOnOff hide = new ButtonOnOff(x, 24, -1, false, "rawmats.gui.button.hide_available", this.tree.getHideAvailable());
        this.addButton(hide, new HideAvailableListener(this));

        if (!this.tree.hasIgnored())
        {
            return;
        }

        // 右端から [除外を解除] [除外中: N] の順で詰める。
        String label = StringUtils.translate("rawmats.gui.button.ignored", this.tree.getIgnored().size());
        ButtonGeneric ignored = new ButtonGeneric(0, 24, -1, 20, label);
        ButtonGeneric clear = new ButtonGeneric(0, 24, -1, 20, StringUtils.translate("rawmats.gui.button.clear_ignored"));

        int ix = this.getScreenWidth() - 12 - ignored.getWidth();
        ignored.setPosition(ix, 24);
        clear.setPosition(ix - 2 - clear.getWidth(), 24);
        this.ignoredBtnX = ix;

        this.addButton(clear, new ClearIgnoredListener(this));
        this.addButton(ignored, new IgnoredListener(this));
    }

    /**
     * 除外中アイテムの一覧メニュー (「除外中」ボタン直下に固定表示)。
     * クリックで個別に解除し、残があればメニューを開き直す (連続解除できる)。
     */
    private void openIgnoredMenu()
    {
        List<Item> ignored = this.tree.getIgnored();

        if (ignored.isEmpty())
        {
            this.popup = null;
            return;
        }

        List<WidgetCraftMenu.Entry> entries = new ArrayList<>();

        for (Item it : ignored)
        {
            Holder<Item> holder = BuiltInRegistries.ITEM.wrapAsHolder(it);
            entries.add(new WidgetCraftMenu.Entry(holder, () -> this.tree.unignore(it)));
        }

        this.popup = new WidgetCraftMenu(this.ignoredBtnX, 46, entries, this::onIgnoredMenuDone);
    }

    private void onIgnoredMenuDone()
    {
        this.rebuildButtons();
        this.build();
        this.openIgnoredMenu();
    }

    /** CraftSteps の集計から DAG ノードとレイアウトを組み立てる。 */
    private void build()
    {
        this.nodes.clear();

        // 再レイアウトで DNode が作り直されるため、旧ノード参照のハイライト状態は破棄する。
        this.hoverNode = null;
        this.hlForward = null;
        this.hlBackward = null;
        this.highlightStrength = 0f;

        this.tree.refreshAvailable();
        CraftTree.NetPass pass = this.tree.computeNetPass();
        this.choosable = pass.choosable;

        Set<Item> rootTypes = new HashSet<>();
        for (CraftNode r : this.tree.getRoots())
        {
            rootTypes.add(r.item.value());
        }

        // ノード登録: frontier (集める/充足で停止、col 0) と crafted (在庫ネット後にクラフトする型)。
        Map<Item, DNode> byItem = new LinkedHashMap<>();

        for (Map.Entry<Item, Long> en : pass.frontierGross.entrySet())
        {
            Item it = en.getKey();

            if (pass.crafted.containsKey(it))
            {
                continue; // cycle 救済の残量は crafted 側の表示を優先
            }

            Holder<Item> holder = BuiltInRegistries.ITEM.wrapAsHolder(it);
            int have = this.tree.availableFor(holder);

            // リストの「在庫済みを隠す」と同じ: 充足した frontier ノードを (辺ごと) 消す。
            // crafted は定義上未充足なので対象外 = グラフの繋がりは壊れない。
            if (this.tree.getHideAvailable() && have >= en.getValue())
            {
                continue;
            }

            DNode n = new DNode(holder, en.getValue(), en.getValue(), false,
                    rootTypes.contains(it), this.choosable.containsKey(it), have);
            n.col = 0;
            byItem.put(it, n);
        }

        List<CraftTree.NetPass.Crafted> crafted = new ArrayList<>(pass.crafted.values());

        for (CraftTree.NetPass.Crafted c : crafted)
        {
            Item it = c.item().value();
            byItem.put(it, new DNode(c.item(), c.gross(), c.toCraft(), true,
                    rootTypes.contains(it), this.choosable.containsKey(it), this.tree.availableFor(c.item())));
        }

        // 列割り当て + 辺: crafted の逆順 = 素材→成果物の順なので、材料の列は (cycle を除き) 決定済み。
        for (int i = crafted.size() - 1; i >= 0; i--)
        {
            CraftTree.NetPass.Crafted c = crafted.get(i);
            DNode out = byItem.get(c.item().value());
            int col = 1;

            for (Item in : c.childCounts().keySet())
            {
                DNode inNode = byItem.get(in);

                if (inNode != null)
                {
                    col = Math.max(col, inNode.col + 1);
                    inNode.consumers.add(out);
                    out.inputs.add(inNode);
                }
            }

            out.col = col;
        }

        this.nodes.addAll(byItem.values());

        // 列ごとにまとめ、barycenter 法で列内順序を整えて y を割り当てる。
        int maxCol = 0;
        for (DNode n : this.nodes)
        {
            maxCol = Math.max(maxCol, n.col);
        }

        List<List<DNode>> cols = new ArrayList<>();
        for (int i = 0; i <= maxCol; i++)
        {
            cols.add(new ArrayList<>());
        }
        for (DNode n : this.nodes)
        {
            cols.get(n.col).add(n);
        }

        for (List<DNode> col : cols)
        {
            assignY(col);
        }

        for (int sweep = 0; sweep < 3; sweep++)
        {
            for (int i = 1; i <= maxCol; i++)
            {
                sortByBarycenter(cols.get(i), true);
            }
            for (int i = maxCol - 1; i >= 0; i--)
            {
                sortByBarycenter(cols.get(i), false);
            }
        }

        // 列 x: 各列の最大ノード幅 + 間隔の累積。
        int x = 0;
        for (List<DNode> col : cols)
        {
            int maxW = 0;
            for (DNode n : col)
            {
                n.x = x;
                maxW = Math.max(maxW, n.width);
            }
            x += maxW + H_GAP;
        }

        this.contentMinX = Integer.MAX_VALUE;
        this.contentMaxX = Integer.MIN_VALUE;
        this.contentMinY = Integer.MAX_VALUE;
        this.contentMaxY = Integer.MIN_VALUE;

        for (DNode n : this.nodes)
        {
            this.contentMinX = Math.min(this.contentMinX, n.x);
            this.contentMaxX = Math.max(this.contentMaxX, n.x + n.width);
            this.contentMinY = Math.min(this.contentMinY, n.y - ROW_H / 2);
            this.contentMaxY = Math.max(this.contentMaxY, n.y + ROW_H / 2);
        }
    }

    /** 列内の並び順どおりに、列全体を縦中央 0 に置く。 */
    private static void assignY(List<DNode> col)
    {
        int n = col.size();
        for (int i = 0; i < n; i++)
        {
            col.get(i).y = (int) ((i - (n - 1) / 2.0) * ROW_PITCH);
        }
    }

    /** 隣接ノード (byInputs=true なら材料、false なら消費先) の平均 y に寄せて並べ替える。 */
    private static void sortByBarycenter(List<DNode> col, boolean byInputs)
    {
        col.sort((a, b) -> Double.compare(barycenter(a, byInputs), barycenter(b, byInputs)));
        assignY(col);
    }

    private static double barycenter(DNode n, boolean byInputs)
    {
        List<DNode> adj = byInputs ? n.inputs : n.consumers;

        if (adj.isEmpty())
        {
            return n.y;
        }

        double sum = 0;
        for (DNode a : adj)
        {
            sum += a.y;
        }
        return sum / adj.size();
    }

    @Override
    protected void drawContents(GuiContext ctx, int mouseX, int mouseY, float partialTicks)
    {
        if (this.nodes.isEmpty())
        {
            String s = StringUtils.translate("rawmats.gui.label.dag.empty");
            ctx.drawString(this.font, s, this.width / 2 - this.getStringWidth(s) / 2, this.height / 2, 0xFFC0C0C0);
            return;
        }

        DNode hover = this.popup == null ? this.hitTest(mouseX, mouseY) : null;
        float strength = this.tickHighlight(hover);

        Matrix3x2fStack pose = ctx.pose();
        pose.pushMatrix();
        pose.translate(this.width / 2f, this.height / 2f);
        pose.scale(this.scale, this.scale);
        pose.translate((float) this.offX, (float) this.offY);

        boolean highlight = strength > 0.01f && this.hlForward != null && this.hlBackward != null;

        for (DNode n : this.nodes)
        {
            for (DNode in : n.inputs)
            {
                int color = COLOR_EDGE;

                if (highlight)
                {
                    // 辺 (in -> n) がホバーからの経路上にあるか: 前方閉包は consumers 側に閉じているので
                    // in が前方閉包にあれば祖先経路、n が後方閉包にあれば材料経路。
                    boolean on = this.hlForward.contains(in) || this.hlBackward.contains(n);
                    color = lerpColor(COLOR_EDGE, on ? COLOR_EDGE_HI : COLOR_EDGE_DIM, strength);
                }

                drawEdge(ctx, in.x + in.width, in.y, n.x, n.y, color);
            }
        }
        for (DNode n : this.nodes)
        {
            this.drawNode(ctx, n);
        }

        // 経路外ノードを暗幕で沈ませる (強度でフェード)。アイテムアイコンは塗りより上の
        // レイヤに乗ることがあるので、elementUp で一段上げてから重ねる。
        int dimAlpha = (int) ((COLOR_NODE_DIM >>> 24) * strength);

        if (highlight && dimAlpha > 3)
        {
            ctx.elementUp();
            int dimColor = (dimAlpha << 24) | (COLOR_NODE_DIM & 0xFFFFFF);

            for (DNode n : this.nodes)
            {
                if (!this.hlForward.contains(n) && !this.hlBackward.contains(n))
                {
                    RenderUtils.drawRect(ctx, n.x, n.y - ROW_H / 2, n.width, ROW_H, dimColor);
                }
            }
        }

        pose.popMatrix();

        String hint = StringUtils.translate("rawmats.gui.label.dag.hint");
        ctx.drawString(this.font, hint, 12, this.height - 14, 0xFF909090);

        if (this.popup != null)
        {
            this.popup.render(ctx, mouseX, mouseY, false);
            return;
        }

        if (hover != null)
        {
            this.drawNodeTooltip(ctx, hover, mouseX, mouseY);
        }
    }

    /**
     * ホバーハイライトの遷移を1フレーム進め、現在の強度 (0..1) を返す。
     * - 同じノードを {@link #HIGHLIGHT_DELAY_MS} ホバーし続けて初めて発動 (通過では光らない)。
     * - 発動/解除はイージングで滑らかに遷移し、閉包はフェードアウト中も直前のものを保持する。
     */
    private float tickHighlight(@Nullable DNode hover)
    {
        long now = System.currentTimeMillis();
        float dt = this.lastFrameMs == 0 ? 0f : Math.min(0.1f, (now - this.lastFrameMs) / 1000f);
        this.lastFrameMs = now;

        if (hover != this.hoverNode)
        {
            this.hoverNode = hover;
            this.hoverSince = now;

            if (hover != null)
            {
                this.hlForward = closure(hover, true);
                this.hlBackward = closure(hover, false);
            }
            // hover == null のときは閉包を残す (フェードアウト用)
        }

        float target = hover != null && now - this.hoverSince >= HIGHLIGHT_DELAY_MS ? 1f : 0f;
        this.highlightStrength += (target - this.highlightStrength) * Math.min(1f, dt * HIGHLIGHT_SPEED);

        if (this.highlightStrength < 0.01f)
        {
            this.highlightStrength = 0f;
        }

        return this.highlightStrength;
    }

    private static int lerpColor(int a, int b, float t)
    {
        int alpha = (int) ((a >>> 24) + ((b >>> 24) - (a >>> 24)) * t);
        int red = (int) (((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t);
        int green = (int) (((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t);
        int blue = (int) ((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    /** start から consumers (forward) / inputs (backward) 方向の到達閉包 (start 自身を含む)。cycle 安全。 */
    private static Set<DNode> closure(DNode start, boolean forward)
    {
        Set<DNode> out = new HashSet<>();
        java.util.ArrayDeque<DNode> queue = new java.util.ArrayDeque<>();
        queue.add(start);

        while (!queue.isEmpty())
        {
            DNode n = queue.poll();

            if (out.add(n))
            {
                queue.addAll(forward ? n.consumers : n.inputs);
            }
        }

        return out;
    }

    private void drawNodeTooltip(GuiContext ctx, DNode n, int mouseX, int mouseY)
    {
        // Tab 長押し: レシピ表示 (リストと同じ)。
        RecipeTooltip recipe = this.recipeCache
                .computeIfAbsent(n.item, it -> Optional.ofNullable(RecipeTooltip.lookup(n.holder)))
                .orElse(null);

        if (recipe != null && KeybindMulti.isKeyDown(KeyCodes.KEY_TAB))
        {
            recipe.render(ctx, mouseX, mouseY, this.width, this.tree);
            return;
        }

        List<Component> lines = new ArrayList<>();
        lines.add(n.stack.getHoverName());
        lines.add(Component.literal(StringUtils.translate("rawmats.gui.label.title.total") + ": " + n.gross)
                .withStyle(st -> st.withColor(0xC0C0C0)));

        // 在庫行: 充足 = 緑、一部 = 金、無し = 灰。
        int haveColor = n.have >= n.gross ? 0x60E060 : (n.have > 0 ? 0xE0B040 : 0x909090);
        lines.add(Component.literal(StringUtils.translate("rawmats.gui.label.title.available") + ": " + n.have)
                .withStyle(st -> st.withColor(haveColor)));

        if (n.craftable)
        {
            lines.add(Component.literal(StringUtils.translate("rawmats.gui.label.dag.to_craft") + ": " + n.shown)
                    .withStyle(st -> st.withColor(0xFFFFFF)));
        }

        CraftNode rep = this.choosable.get(n.item);

        if (rep != null && rep.choiceKey != null)
        {
            lines.add(Component.literal("#" + rep.choiceKey.location()).withStyle(st -> st.withColor(0xE0B040)));
        }

        if (this.tree.isExpandable(n.item))
        {
            lines.add(hintLine("rawmats.gui.label.dag.click_expand"));
        }
        if (this.tree.isExpandedType(n.item))
        {
            lines.add(hintLine("rawmats.gui.label.dag.click_fold"));
        }
        else if (!n.consumers.isEmpty())
        {
            lines.add(hintLine("rawmats.gui.label.dag.click_fold_into"));
        }
        lines.add(hintLine("rawmats.gui.label.dag.click_ignore"));
        if (rep != null)
        {
            lines.add(hintLine("rawmats.gui.label.dag.click_choose"));
        }
        if (recipe != null)
        {
            lines.add(hintLine("rawmats.gui.label.recipe.hint"));
        }

        //? if >=26.1 {
        ctx.renderTooltip(this.font, lines, mouseX, mouseY);
        //?} else {
        /*ctx.setComponentTooltipForNextFrame(this.font, lines, mouseX, mouseY);
        *///?}
    }

    private static Component hintLine(String key)
    {
        return Component.literal(StringUtils.translate(key)).withStyle(st -> st.withColor(0x909090));
    }

    private void drawNode(GuiContext ctx, DNode n)
    {
        if (n.craftable)
        {
            int border = n.isRoot ? COLOR_BOX_BORDER_ROOT : COLOR_BOX_BORDER;
            RenderUtils.drawOutlinedBox(ctx, n.x, n.y - ROW_H / 2 + 1, n.width, ROW_H - 2, COLOR_BOX_BG, border);
        }

        RenderUtils.drawRect(ctx, n.x + 2, n.y - 8, 16, 16, n.craftable ? 0x20FFFFFF : COLOR_LEAF_BG);
        ctx.renderItem(n.stack, n.x + 2, n.y - 8);
        ctx.drawString(this.font, n.countText, n.x + 21, n.y - 4, n.countColor);

        if (n.covered)
        {
            int cx = n.x + 21 + StringUtils.getStringWidth(n.countText) + 3;
            ctx.blitSprite(RenderPipelines.GUI_TEXTURED, CHECKMARK_SPRITE, cx, n.y - 4, 9, 8, COLOR_COVERED);
        }

        // 展開可能 (レシピはあるが未展開) は、隠れている材料側 (左) に "+" を出す。
        if (this.tree.isExpandable(n.item))
        {
            ctx.drawString(this.font, "+", n.x - StringUtils.getStringWidth("+") - 3, n.y - 4, COLOR_MARKER);
        }

        // タグ素材変更が可能なら "*" を右端に出す (リストの [*] 相当)。
        if (n.choosable)
        {
            ctx.drawString(this.font, "*", n.x + n.width - 8, n.y - 4, COLOR_MARKER);
        }
    }

    /** 任意角度の直線: 始点へ平行移動 + 回転して 1px 高の矩形を塗る。 */
    private static void drawEdge(GuiContext ctx, int x1, int y1, int x2, int y2, int color)
    {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float len = (float) Math.hypot(dx, dy);

        Matrix3x2fStack pose = ctx.pose();
        pose.pushMatrix();
        pose.translate(x1, y1);
        pose.rotate((float) Math.atan2(dy, dx));
        ctx.fill(0, 0, (int) len + 1, 1, color);
        pose.popMatrix();
    }

    @Nullable
    private DNode hitTest(double mouseX, double mouseY)
    {
        double lx = (mouseX - this.width / 2.0) / this.scale - this.offX;
        double ly = (mouseY - this.height / 2.0) / this.scale - this.offY;

        for (DNode n : this.nodes)
        {
            if (lx >= n.x && lx < n.x + n.width && ly >= n.y - ROW_H / 2 && ly < n.y + ROW_H / 2)
            {
                return n;
            }
        }

        return null;
    }

    @Override
    public boolean onMouseClicked(MouseButtonEvent click, boolean doubleClick)
    {
        if (this.popup != null)
        {
            if (this.popup.isMouseOver((int) click.x(), (int) click.y()))
            {
                this.popup.onMouseClicked(click, doubleClick);
            }
            else
            {
                this.popup = null;
            }
            return true;
        }

        if (super.onMouseClicked(click, doubleClick))
        {
            return true;
        }

        DNode hit = this.hitTest(click.x(), click.y());

        if (hit != null)
        {
            // リストと同じ操作系: 左 = 展開、右 = 畳む (Shift+右 = 除外)、中 = タグ素材の変更。
            if (click.input() == 0 && this.tree.isExpandable(hit.item))
            {
                this.tree.expand(hit.item);
                this.build();
                return true;
            }
            else if (click.input() == 1)
            {
                if (isShiftDown())
                {
                    // 除外は任意のノードを対象にできる。成果物/中間素材なら材料需要ごと消える。
                    this.tree.ignore(hit.item);
                    this.rebuildButtons();
                    this.build();
                    return true;
                }
                else if (this.tree.isExpandedType(hit.item))
                {
                    this.tree.collapse(hit.item);
                    this.build();
                    return true;
                }
                else if (!hit.consumers.isEmpty())
                {
                    // 自身は畳めない frontier (レシピ無し等): リストの fold と同じく、
                    // 消費先 (右の親) を畳む。複数ならどれを畳むか選ばせる。
                    if (hit.consumers.size() == 1)
                    {
                        this.tree.collapse(hit.consumers.get(0).item);
                        this.build();
                    }
                    else
                    {
                        List<WidgetCraftMenu.Entry> entries = new ArrayList<>();

                        for (DNode c : hit.consumers)
                        {
                            entries.add(new WidgetCraftMenu.Entry(c.holder, () -> this.tree.collapse(c.item)));
                        }

                        this.openPopupAt((int) click.x(), (int) click.y(), entries);
                    }
                    return true;
                }
            }
            else if (click.input() == 2)
            {
                CraftNode rep = this.choosable.get(hit.item);

                if (rep != null && rep.isChoosable())
                {
                    this.openChoicePopup(rep, (int) click.x(), (int) click.y());
                    return true;
                }
            }
        }

        this.dragging = true;
        return true;
    }

    /** タグ素材の候補ポップアップ (リストの中クリックと同じ UI)。 */
    private void openChoicePopup(CraftNode rep, int mouseX, int mouseY)
    {
        List<WidgetCraftMenu.Entry> entries = new ArrayList<>();

        for (Holder<Item> cand : rep.choices)
        {
            entries.add(new WidgetCraftMenu.Entry(cand, () -> this.tree.chooseMaterial(rep.choiceKey, cand)));
        }

        this.openPopupAt(mouseX, mouseY, entries);
    }

    /** アイコンポップアップをマウス位置に、画面内にクランプして開く。 */
    private void openPopupAt(int mouseX, int mouseY, List<WidgetCraftMenu.Entry> entries)
    {
        WidgetCraftMenu menu = new WidgetCraftMenu(mouseX, mouseY, entries, this::onMenuActionDone);
        int px = Math.min(mouseX, this.getScreenWidth() - menu.getWidth() - 4);
        int py = Math.min(mouseY, this.getScreenHeight() - menu.getHeight() - 4);

        if (px != mouseX || py != mouseY)
        {
            menu = new WidgetCraftMenu(Math.max(2, px), Math.max(2, py), entries, this::onMenuActionDone);
        }

        this.popup = menu;
    }

    private void onMenuActionDone()
    {
        this.popup = null;
        this.build();
    }

    @Override
    public boolean onKeyTyped(KeyEvent input)
    {
        if (this.popup != null && input.key() == KeyCodes.KEY_ESCAPE)
        {
            this.popup = null;
            return true;
        }

        return super.onKeyTyped(input);
    }

    @Override
    public boolean onMouseReleased(MouseButtonEvent click)
    {
        this.dragging = false;
        return super.onMouseReleased(click);
    }

    @Override
    public boolean onMouseDragged(MouseButtonEvent click, double deltaX, double deltaY)
    {
        if (this.dragging)
        {
            this.offX += deltaX / this.scale;
            this.offY += deltaY / this.scale;
            return true;
        }

        return super.onMouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean onMouseScrolled(double mouseX, double mouseY, double horizontal, double vertical)
    {
        if (vertical == 0)
        {
            return super.onMouseScrolled(mouseX, mouseY, horizontal, vertical);
        }

        float newScale = vertical > 0 ? this.scale * 1.25f : this.scale / 1.25f;
        newScale = Math.max(0.25f, Math.min(3.0f, newScale));

        if (newScale != this.scale)
        {
            double px = (mouseX - this.width / 2.0) / this.scale - this.offX;
            double py = (mouseY - this.height / 2.0) / this.scale - this.offY;
            this.scale = newScale;
            this.offX = (mouseX - this.width / 2.0) / this.scale - px;
            this.offY = (mouseY - this.height / 2.0) / this.scale - py;
        }

        return true;
    }

    /** DAG の 1 ノード (アイテム種別単位)。craftable = 在庫ネット後にクラフトする型 (材料の辺を持つ)。 */
    private static class DNode
    {
        final Holder<Item> holder;
        final Item item;
        /** 総需要。 */
        final long gross;
        /** 表示する数。frontier は総需要、crafted は在庫ネット後のクラフト数 (toCraft)。 */
        final long shown;
        final boolean craftable;
        final boolean isRoot;
        final boolean choosable;
        final int have;
        final ItemStack stack;
        final String countText;
        final int countColor;
        /** 在庫で充足 (チェックマークを描く)。 */
        final boolean covered;
        final int width;
        final List<DNode> inputs = new ArrayList<>();
        final List<DNode> consumers = new ArrayList<>();
        int col;
        int x, y;

        DNode(Holder<Item> holder, long gross, long shown, boolean craftable,
                boolean isRoot, boolean choosable, int have)
        {
            this.holder = holder;
            this.item = holder.value();
            this.gross = gross;
            this.shown = shown;
            this.craftable = craftable;
            this.isRoot = isRoot;
            this.choosable = choosable;
            this.have = have;
            this.stack = new ItemStack(holder);

            // 在庫表示 (frontier / crafted 共通): 充足 = 緑 + チェック (スプライト)、
            // 一部 = 金で have/gross、無し = 白で gross。crafted のクラフト数はツールチップで見せる。
            // (crafted は toCraft > 0 = 未充足なので ✓ にはならない)
            if (have >= gross)
            {
                this.countText = "x" + fmtCount(gross);
                this.countColor = COLOR_COVERED;
                this.covered = true;
            }
            else if (have > 0)
            {
                this.countText = fmtCount(have) + "/" + fmtCount(gross);
                this.countColor = 0xFFE0B040;
                this.covered = false;
            }
            else
            {
                this.countText = "x" + fmtCount(gross);
                this.countColor = 0xFFFFFFFF;
                this.covered = false;
            }

            this.width = 2 + 16 + 3 + StringUtils.getStringWidth(this.countText)
                    + (this.covered ? 12 : 0) + (choosable ? 7 : 0) + 4;
        }

        private static String fmtCount(long n)
        {
            return n >= 100000 ? (n / 1000) + "k" : String.valueOf(n);
        }
    }

    /** ツリー状態を変えるだけの汎用リスナ (実行後に再レイアウト)。 */
    private record ActionListener(GuiCraftDag parent, Runnable action) implements IButtonActionListener
    {
        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton)
        {
            this.action.run();
            this.parent.popup = null;
            this.parent.build();
        }
    }

    private record HideAvailableListener(GuiCraftDag parent) implements IButtonActionListener
    {
        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton)
        {
            this.parent.tree.toggleHideAvailable();
            this.parent.popup = null;
            this.parent.rebuildButtons();
            this.parent.build();
        }
    }

    private record ClearIgnoredListener(GuiCraftDag parent) implements IButtonActionListener
    {
        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton)
        {
            this.parent.tree.clearIgnored();
            this.parent.popup = null;
            this.parent.rebuildButtons();
            this.parent.build();
        }
    }

    private record IgnoredListener(GuiCraftDag parent) implements IButtonActionListener
    {
        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton)
        {
            this.parent.openIgnoredMenu();
        }
    }
}
