package io.github.naari3.rawmats.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import org.joml.Matrix3x2fStack;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.StringUtils;

import io.github.naari3.rawmats.materials.CraftNode;
import io.github.naari3.rawmats.materials.CraftTree;

/**
 * ベイク済みクラフトツリーを「素材が左、成果物が右」の家系図状に描く画面 (EMI の BoM 画面を参考)。
 * クラフト作業の時間順 (素材を集める → 中間素材 → 完成品) と同じ向きで読める。
 *
 * - レイアウトは EMI BoMScreen の TreeVolume (部分木の輪郭を詰める整然木配置) を縦横転置した自前実装。
 *   root (schematic 直材) が右端の列、材料が左の列へ深さ順に並ぶ。共有素材は出現ごとに別ノード
 *   (DAG マージはしない。合算はフラットリスト側の役割)。
 * - 操作: ドラッグ = パン、スクロール = ズーム (マウス位置基準)、子持ちノードのクリック = 折りたたみ。
 * - 表示はベイク済みツリーそのもの (在庫差引なしの総需要)。リスト側の展開状態とは独立。
 */
public class GuiCraftGraph extends GuiBase
{
    /** 列間隔 (ノード + 辺スペース)。 */
    private static final int COL_PITCH = 80;
    /** ノード 1 個の縦方向の占有高 (輪郭詰めの単位)。 */
    private static final int ROW_H = 20;
    /** 兄弟部分木同士の縦の隙間。 */
    private static final int SIB_GAP = 4;
    /** 子列の左端から何 px 左にバス縦線を置くか。 */
    private static final int BUS_INSET = 6;

    private static final int COLOR_EDGE = 0xFFA0A0A0;
    private static final int COLOR_BOX_BG = 0xE0101010;
    private static final int COLOR_BOX_BORDER = 0xFFC0C0C0;
    private static final int COLOR_BOX_BORDER_ROOT = 0xFFE0B040;
    private static final int COLOR_LEAF_BG = 0x4060B0FF;
    private static final int COLOR_MARKER = 0xFFFFC800;

    private final CraftTree tree;
    private final List<GNode> nodes = new ArrayList<>();
    /** 折りたたみ中のノード (ノード単位。リスト側のアイテム種別単位の展開とは別物)。 */
    private final Set<CraftNode> collapsedNodes = Collections.newSetFromMap(new IdentityHashMap<>());

    private double offX, offY;
    private float scale = 1.0f;
    private boolean dragging;
    private int contentMinY, contentMaxY, contentMinX, contentMaxX;

    public GuiCraftGraph(CraftTree tree)
    {
        this.tree = tree;
        this.title = StringUtils.translate("rawmats.gui.title.craft_graph");
        this.useTitleHierarchy = false;
    }

    @Override
    public void initGui()
    {
        super.initGui();
        this.reLayout();
        // 初期表示は内容全体の中心を画面中心へ (リサイズ時も同様に置き直す)。
        this.offX = -((this.contentMinX + this.contentMaxX) / 2.0);
        this.offY = -((this.contentMinY + this.contentMaxY) / 2.0);
    }

    /** レイアウトを作り直す (初期化時と折りたたみ切替時)。 */
    private void reLayout()
    {
        this.nodes.clear();
        TreeVolume forest = null;

        for (CraftNode r : this.tree.getRoots())
        {
            TreeVolume v = this.layoutNode(r, 0);
            forest = forest == null ? v : merge(forest, v);
        }

        if (forest == null)
        {
            this.contentMinY = 0;
            this.contentMaxY = 0;
            this.contentMinX = 0;
            this.contentMaxX = 0;
            return;
        }

        this.nodes.addAll(forest.nodes);
        this.contentMinY = forest.minTop();
        this.contentMaxY = forest.maxBottom();

        this.contentMinX = Integer.MAX_VALUE;
        this.contentMaxX = Integer.MIN_VALUE;
        for (GNode g : this.nodes)
        {
            this.contentMinX = Math.min(this.contentMinX, g.x);
            this.contentMaxX = Math.max(this.contentMaxX, g.x + g.width);
        }
    }

    /** node 以下を再帰レイアウトして部分木ボリュームを返す (EMI addNewNodes の転置)。 */
    private TreeVolume layoutNode(CraftNode node, int col)
    {
        GNode g = new GNode(node, col);

        if (!node.children.isEmpty() && !this.collapsedNodes.contains(node))
        {
            TreeVolume merged = null;

            for (CraftNode c : node.children)
            {
                TreeVolume v = this.layoutNode(c, col + 1);
                g.children.add(v.head);
                merged = merged == null ? v : merge(merged, v);
            }

            merged.addHead(g);
            return merged;
        }

        return new TreeVolume(g);
    }

    private static TreeVolume merge(TreeVolume upper, TreeVolume lower)
    {
        upper.addBelow(lower);
        return upper;
    }

    @Override
    protected void drawContents(GuiContext ctx, int mouseX, int mouseY, float partialTicks)
    {
        if (this.nodes.isEmpty())
        {
            String s = StringUtils.translate("rawmats.gui.label.graph.empty");
            ctx.drawString(this.font, s, this.width / 2 - this.getStringWidth(s) / 2, this.height / 2, 0xFFC0C0C0);
            return;
        }

        Matrix3x2fStack pose = ctx.pose();
        pose.pushMatrix();
        pose.translate(this.width / 2f, this.height / 2f);
        pose.scale(this.scale, this.scale);
        pose.translate((float) this.offX, (float) this.offY);

        for (GNode g : this.nodes)
        {
            this.drawEdges(ctx, g);
        }
        for (GNode g : this.nodes)
        {
            this.drawNode(ctx, g);
        }

        pose.popMatrix();

        String hint = StringUtils.translate("rawmats.gui.label.graph.hint");
        ctx.drawString(this.font, hint, 12, this.height - 14, 0xFF909090);

        GNode hover = this.hitTest(mouseX, mouseY);

        if (hover != null)
        {
            this.drawNodeTooltip(ctx, hover, mouseX, mouseY);
        }
    }

    /** 展開中の各子 (左の素材) から親 g (右の成果物) への直交辺 (子右端 → バス縦線 → 親左端)。 */
    private void drawEdges(GuiContext ctx, GNode g)
    {
        if (g.children.isEmpty())
        {
            return;
        }

        // 子列 (col+1) の右端揃えの位置から BUS_INSET だけ親側に寄せた縦バス。
        int busX = -((g.col + 1) * COL_PITCH) + BUS_INSET;
        hLine(ctx, busX, g.x, g.y);

        for (GNode c : g.children)
        {
            vLine(ctx, busX, g.y, c.y);
            hLine(ctx, c.x + c.width, busX, c.y);
        }
    }

    private void drawNode(GuiContext ctx, GNode g)
    {
        boolean expandable = !g.node.children.isEmpty();
        boolean folded = expandable && this.collapsedNodes.contains(g.node);

        if (expandable)
        {
            int border = g.col == 0 ? COLOR_BOX_BORDER_ROOT : COLOR_BOX_BORDER;
            RenderUtils.drawOutlinedBox(ctx, g.x, g.y - ROW_H / 2 + 1, g.width, ROW_H - 2, COLOR_BOX_BG, border);
        }

        int iconX = g.x + 2;
        RenderUtils.drawRect(ctx, iconX, g.y - 8, 16, 16, g.node.children.isEmpty() ? COLOR_LEAF_BG : 0x20FFFFFF);
        ctx.renderItem(g.stack, iconX, g.y - 8);
        ctx.drawString(this.font, g.countText, iconX + 19, g.y - 4, 0xFFFFFFFF);

        if (folded)
        {
            // 隠れている部分木がある側 (左) にマーカーを出す。
            ctx.drawString(this.font, "+", g.x - StringUtils.getStringWidth("+") - 3, g.y - 4, COLOR_MARKER);
        }
    }

    private void drawNodeTooltip(GuiContext ctx, GNode g, int mouseX, int mouseY)
    {
        List<Component> lines = new ArrayList<>();
        lines.add(g.stack.getHoverName());
        lines.add(Component.literal("x" + g.node.count).withStyle(s -> s.withColor(0xC0C0C0)));

        if (g.node.choiceKey != null)
        {
            lines.add(Component.literal("#" + g.node.choiceKey.location()).withStyle(s -> s.withColor(0xE0B040)));
        }

        if (!g.node.children.isEmpty())
        {
            String key = this.collapsedNodes.contains(g.node)
                    ? "rawmats.gui.label.graph.click_expand" : "rawmats.gui.label.graph.click_fold";
            lines.add(Component.literal(StringUtils.translate(key)).withStyle(s -> s.withColor(0x909090)));
        }

        ctx.renderTooltip(this.font, lines, mouseX, mouseY);
    }

    private static void hLine(GuiContext ctx, int x1, int x2, int y)
    {
        ctx.fill(Math.min(x1, x2), y, Math.max(x1, x2) + 1, y + 1, COLOR_EDGE);
    }

    private static void vLine(GuiContext ctx, int x, int y1, int y2)
    {
        ctx.fill(x, Math.min(y1, y2), x + 1, Math.max(y1, y2) + 1, COLOR_EDGE);
    }

    /** 画面座標 → レイアウト座標に逆変換してノードを当てる。 */
    @Nullable
    private GNode hitTest(double mouseX, double mouseY)
    {
        double lx = (mouseX - this.width / 2.0) / this.scale - this.offX;
        double ly = (mouseY - this.height / 2.0) / this.scale - this.offY;

        for (GNode g : this.nodes)
        {
            if (lx >= g.x && lx < g.x + g.width && ly >= g.y - ROW_H / 2 && ly < g.y + ROW_H / 2)
            {
                return g;
            }
        }

        return null;
    }

    @Override
    public boolean onMouseClicked(MouseButtonEvent click, boolean doubleClick)
    {
        if (super.onMouseClicked(click, doubleClick))
        {
            return true;
        }

        GNode hit = this.hitTest(click.x(), click.y());

        if (hit != null && !hit.node.children.isEmpty())
        {
            if (!this.collapsedNodes.remove(hit.node))
            {
                this.collapsedNodes.add(hit.node);
            }

            this.reLayout();
            return true;
        }

        this.dragging = true;
        return true;
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
            // マウス位置のレイアウト座標を固定点にしてズームする。
            double px = (mouseX - this.width / 2.0) / this.scale - this.offX;
            double py = (mouseY - this.height / 2.0) / this.scale - this.offY;
            this.scale = newScale;
            this.offX = (mouseX - this.width / 2.0) / this.scale - px;
            this.offY = (mouseY - this.height / 2.0) / this.scale - py;
        }

        return true;
    }

    /**
     * グラフ上の 1 ノード。x/col は列で決まり、y はレイアウトが決める (中心座標)。
     * x は負方向 (col が深いほど左)。列内では右端 (-col * COL_PITCH) が揃う。
     */
    private static class GNode
    {
        final CraftNode node;
        final int col;
        final int x;
        final int width;
        final ItemStack stack;
        final String countText;
        final List<GNode> children = new ArrayList<>();
        int y;

        GNode(CraftNode node, int col)
        {
            this.node = node;
            this.col = col;
            this.stack = new ItemStack(node.item);
            this.countText = "x" + fmtCount(node.count);
            this.width = 2 + 16 + 3 + StringUtils.getStringWidth(this.countText) + 4;
            this.x = -(col * COL_PITCH) - this.width;
        }

        private static String fmtCount(int n)
        {
            return n >= 100000 ? (n / 1000) + "k" : String.valueOf(n);
        }
    }

    /**
     * 部分木のノード群 + 相対列ごとの縦の占有範囲 [top, bottom] (輪郭)。
     * EMI BoMScreen.TreeVolume の縦横転置。addBelow が輪郭同士を全列で比較し、
     * 重ならない最小シフトで下に詰める。addHead が親を子スパンの縦中央に置く。
     */
    private static class TreeVolume
    {
        final List<GNode> nodes = new ArrayList<>();
        /** 相対列ごとの {top, bottom}。index 0 = この部分木の根の列。 */
        final List<int[]> cols = new ArrayList<>();
        GNode head;

        TreeVolume(GNode head)
        {
            head.y = 0;
            this.head = head;
            this.nodes.add(head);
            this.cols.add(new int[] { -ROW_H / 2, ROW_H / 2 });
        }

        void addBelow(TreeVolume other)
        {
            int shift = 0;

            for (int i = 0; i < this.cols.size() && i < other.cols.size(); i++)
            {
                shift = Math.max(shift, this.cols.get(i)[1] - other.cols.get(i)[0] + SIB_GAP);
            }

            for (int i = 0; i < other.cols.size(); i++)
            {
                if (i < this.cols.size())
                {
                    this.cols.get(i)[1] = other.cols.get(i)[1] + shift;
                }
                else
                {
                    this.cols.add(new int[] { other.cols.get(i)[0] + shift, other.cols.get(i)[1] + shift });
                }
            }

            for (GNode g : other.nodes)
            {
                g.y += shift;
                this.nodes.add(g);
            }
        }

        /** 親を子スパン (現ボリュームの列 0 = 直接の子の列) の縦中央に置き、列を 1 つ前に挿入する。 */
        void addHead(GNode g)
        {
            g.y = (this.cols.get(0)[0] + this.cols.get(0)[1]) / 2;
            this.head = g;
            this.nodes.add(0, g);
            this.cols.add(0, new int[] { g.y - ROW_H / 2, g.y + ROW_H / 2 });
        }

        int minTop()
        {
            int m = Integer.MAX_VALUE;
            for (int[] c : this.cols)
            {
                m = Math.min(m, c[0]);
            }
            return m;
        }

        int maxBottom()
        {
            int m = Integer.MIN_VALUE;
            for (int[] c : this.cols)
            {
                m = Math.max(m, c[1]);
            }
            return m;
        }
    }
}
