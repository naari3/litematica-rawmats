package io.github.naari3.rawmats.gui;

import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.item.ItemStack;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.gui.widgets.WidgetListEntrySortable;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.StringUtils;
import fi.dy.masa.litematica.gui.Icons;

import io.github.naari3.rawmats.materials.CraftTree;
import io.github.naari3.rawmats.materials.MatRow;

/**
 * フラット買い物リストの 1 行。本家 {@code WidgetMaterialListEntry} に寄せた 4 列レイアウト
 * (Item / Total / Missing / Available)。ヘッダ行 (row == null) は列タイトル + ソート矢印を出し、
 * クリックでソート列を切り替える。
 *
 * 専用機能はマーカーで残す: 左クリック展開 / 右クリック畳み / 中クリックでタグ材料の素材選択。
 * マーカー [+]=展開可 / [-]=畳み可 / [±]=両方、[*]=タグ材料 (素材選択可)。
 */
public class WidgetCraftTreeEntry extends WidgetListEntrySortable<MatRow>
{
    private static final String[] HEADERS = {
            "rawmats.gui.label.title.item",
            "rawmats.gui.label.title.total",
            "rawmats.gui.label.title.missing",
            "rawmats.gui.label.title.available" };

    private static int maxNameLength;
    private static int maxTotalLength;
    private static int maxMissingLength;
    private static int maxAvailLength;

    /** 展開/畳みマーカー用に列 0 の左端へ予約する幅。 */
    private static final int MARK_W = 14;

    @Nullable private final MatRow row;
    private final WidgetListCraftTree listWidget;
    private final boolean isOdd;
    private final String shulkerAbbr;
    @Nullable private final String header1;
    @Nullable private final String header2;
    @Nullable private final String header3;
    @Nullable private final String header4;

    public WidgetCraftTreeEntry(int x, int y, int width, int height, boolean isOdd,
            @Nullable MatRow row, int listIndex, WidgetListCraftTree listWidget)
    {
        super(x, y, width, height, row, listIndex);

        this.columnCount = 4;
        this.row = row;
        this.isOdd = isOdd;
        this.listWidget = listWidget;
        this.shulkerAbbr = StringUtils.translate("rawmats.gui.label.abbr.shulker_box");

        if (row != null)
        {
            this.header1 = null;
            this.header2 = null;
            this.header3 = null;
            this.header4 = null;
        }
        else
        {
            this.header1 = GuiBase.TXT_BOLD + StringUtils.translate(HEADERS[0]) + GuiBase.TXT_RST;
            this.header2 = GuiBase.TXT_BOLD + StringUtils.translate(HEADERS[1]) + GuiBase.TXT_RST;
            this.header3 = GuiBase.TXT_BOLD + StringUtils.translate(HEADERS[2]) + GuiBase.TXT_RST;
            this.header4 = GuiBase.TXT_BOLD + StringUtils.translate(HEADERS[3]) + GuiBase.TXT_RST;
        }

        // 行ごとの除外 (ignore) ボタンを右端に置く (本家 WidgetMaterialListEntry に倣う)。
        if (row != null)
        {
            String label = StringUtils.translate("rawmats.gui.button.ignore");
            this.addButton(new ButtonGeneric(x + width - 2, y + 2, -1, true, label), new IgnoreListener(this, row));
        }
    }

    /** 列幅をリスト内容から決める (描画前に呼ぶ)。 */
    public static void setMaxLengths(List<MatRow> rows)
    {
        maxNameLength    = StringUtils.getStringWidth(GuiBase.TXT_BOLD + StringUtils.translate(HEADERS[0]) + GuiBase.TXT_RST);
        maxTotalLength   = StringUtils.getStringWidth(GuiBase.TXT_BOLD + StringUtils.translate(HEADERS[1]) + GuiBase.TXT_RST);
        maxMissingLength = StringUtils.getStringWidth(GuiBase.TXT_BOLD + StringUtils.translate(HEADERS[2]) + GuiBase.TXT_RST);
        maxAvailLength   = StringUtils.getStringWidth(GuiBase.TXT_BOLD + StringUtils.translate(HEADERS[3]) + GuiBase.TXT_RST);

        for (MatRow r : rows)
        {
            String name = new ItemStack(r.item).getHoverName().getString();
            maxNameLength    = Math.max(maxNameLength,    StringUtils.getStringWidth(name));
            maxTotalLength   = Math.max(maxTotalLength,   StringUtils.getStringWidth(String.valueOf(r.total)));
            maxMissingLength = Math.max(maxMissingLength, StringUtils.getStringWidth(String.valueOf(r.need)));
            maxAvailLength   = Math.max(maxAvailLength,   StringUtils.getStringWidth(String.valueOf(r.have)));
        }
    }

    @Override
    public boolean canSelectAt(MouseButtonEvent click)
    {
        return false;
    }

    @Override
    protected int getCurrentSortColumn()
    {
        return this.listWidget.getTree().getSortColumn().ordinal();
    }

    @Override
    protected boolean getSortInReverse()
    {
        return this.listWidget.getTree().getSortInReverse();
    }

    @Override
    protected int getColumnPosX(int column)
    {
        int x1 = this.x + 4;
        int x2 = x1 + MARK_W + 20 + maxNameLength + 16; // marker + icon + name + gap
        int x3 = x2 + maxTotalLength + 20;
        int x4 = x3 + maxMissingLength + 20;

        return switch (column)
        {
            case 0 -> x1;
            case 1 -> x2;
            case 2 -> x3;
            case 3 -> x4;
            case 4 -> x4 + maxAvailLength + 20;
            default -> x1;
        };
    }

    @Override
    protected boolean onMouseClickedImpl(MouseButtonEvent click, boolean doubleClick)
    {
        if (this.listWidget.getGui().hasPopup())
        {
            return false;
        }

        // ヘッダ行: 列クリックでソート切替。
        if (this.row == null)
        {
            int column = this.getMouseOverColumn((int) click.x(), (int) click.y());
            CraftTree tree = this.listWidget.getTree();

            switch (column)
            {
                case 0 -> tree.setSortColumn(CraftTree.SortColumn.NAME);
                case 1 -> tree.setSortColumn(CraftTree.SortColumn.TOTAL);
                case 2 -> tree.setSortColumn(CraftTree.SortColumn.MISSING);
                case 3 -> tree.setSortColumn(CraftTree.SortColumn.AVAILABLE);
                default -> { return false; }
            }

            this.listWidget.refreshEntries();
            return true;
        }

        if (!this.isMouseOver((int) click.x(), (int) click.y()))
        {
            return false;
        }

        // 左クリック = 展開、右クリック = 親へ畳む、中クリック = タグ材料の素材選択
        if (click.input() == 0 && this.row.expandable)
        {
            this.listWidget.getGui().expandRow(this.row);
            return true;
        }
        else if (click.input() == 1 && this.row.foldable)
        {
            this.listWidget.getGui().foldRow(this.row, (int) click.x(), (int) click.y());
            return true;
        }
        else if (click.input() == 2 && this.row.choosable)
        {
            this.listWidget.getGui().chooseRow(this.row, (int) click.x(), (int) click.y());
            return true;
        }

        return false;
    }

    @Override
    public void render(GuiContext ctx, int mouseX, int mouseY, boolean selected)
    {
        // 背景 (ホバー / 偶奇)
        if (this.row != null && this.isMouseOver(mouseX, mouseY) && !this.listWidget.getGui().hasPopup())
        {
            RenderUtils.drawRect(ctx, this.x, this.y, this.width, this.height, 0xA0707070);
        }
        else if (this.isOdd)
        {
            RenderUtils.drawRect(ctx, this.x, this.y, this.width, this.height, 0xA0101010);
        }
        else
        {
            RenderUtils.drawRect(ctx, this.x, this.y, this.width, this.height, 0xA0303030);
        }

        int x1 = this.getColumnPosX(0);
        int x2 = this.getColumnPosX(1);
        int x3 = this.getColumnPosX(2);
        int x4 = this.getColumnPosX(3);
        int y = this.y + 7;
        int color = 0xFFFFFFFF;

        if (this.row == null)
        {
            // 検索バーを開いている間はヘッダを隠す (重なり回避、本家と同じ)。
            if (this.listWidget.getSearchBarWidget() == null || !this.listWidget.getSearchBarWidget().isSearchOpen())
            {
                this.drawString(ctx, x1, y, color, this.header1);
                this.drawString(ctx, x2, y, color, this.header2);
                this.drawString(ctx, x3, y, color, this.header3);
                this.drawString(ctx, x4, y, color, this.header4);
                this.renderColumnHeader(ctx, mouseX, mouseY, Icons.ARROW_DOWN, Icons.ARROW_UP);
            }
            return;
        }

        String green = GuiBase.TXT_GREEN;
        String gold = GuiBase.TXT_GOLD;
        String red = GuiBase.TXT_RED;

        // 操作マーカー
        String mark = this.row.expandable && this.row.foldable ? "[±]"
                : this.row.expandable ? "[+]"
                : this.row.foldable ? "[-]" : "";
        if (!mark.isEmpty())
        {
            this.drawString(ctx, x1, y, color, mark);
        }

        // アイコン + 名前
        int iconX = x1 + MARK_W;
        ItemStack stack = new ItemStack(this.row.item);
        RenderUtils.drawRect(ctx, iconX, this.y + 3, 16, 16, 0x20FFFFFF);
        ctx.renderItem(stack, iconX, this.y + 3);
        String name = stack.getHoverName().getString();
        this.drawString(ctx, iconX + 20, y, color, name);

        // タグ材料 (中クリックで素材選択可) のマーカー
        if (this.row.choosable)
        {
            int mx = iconX + 20 + this.getStringWidth(name) + 4;
            this.drawString(ctx, mx, y, color, gold + "[*]");
        }

        // Total
        this.drawString(ctx, x2, y, color, String.valueOf(this.row.total));

        // Missing (= need、在庫差引後): 充足(0)=緑、在庫で賄える=金、不足=赤
        String preMissing = this.row.need == 0 ? green : (this.row.have >= this.row.need ? gold : red);
        this.drawString(ctx, x3, y, color, preMissing + this.row.need);

        // Available (= have): 充足してるなら緑、不足なら赤
        String preAvail = this.row.have >= this.row.need ? green : red;
        this.drawString(ctx, x4, y, color, preAvail + this.row.have);

        // 行内ボタン (ignore) を描画。
        this.drawSubWidgets(ctx, mouseX, mouseY);
    }

    @Override
    public void postRenderHovered(GuiContext ctx, int mouseX, int mouseY, boolean selected)
    {
        if (this.row == null || this.listWidget.getGui().hasPopup())
        {
            return;
        }

        String hItem    = GuiBase.TXT_BOLD + StringUtils.translate(HEADERS[0]);
        String hTotal   = GuiBase.TXT_BOLD + StringUtils.translate(HEADERS[1]);
        String hMissing = GuiBase.TXT_BOLD + StringUtils.translate(HEADERS[2]);
        String hAvail   = GuiBase.TXT_BOLD + StringUtils.translate(HEADERS[3]);

        ItemStack stack = new ItemStack(this.row.item);
        String name = stack.getHoverName().getString();
        int maxStack = stack.getMaxStackSize();
        String sTotal   = this.getFormattedCountString(this.row.total, maxStack);
        String sMissing = this.getFormattedCountString(this.row.need, maxStack);
        String sAvail   = this.getFormattedCountString(this.row.have, maxStack);

        int w1 = Math.max(this.getStringWidth(hItem),
                 Math.max(this.getStringWidth(hTotal),
                 Math.max(this.getStringWidth(hMissing), this.getStringWidth(hAvail))));
        int w2 = Math.max(this.getStringWidth(name) + 20,
                 Math.max(this.getStringWidth(sTotal),
                 Math.max(this.getStringWidth(sMissing), this.getStringWidth(sAvail))));
        int boxWidth = w1 + w2 + 60;
        int boxHeight = 76;

        int x = mouseX + 10;
        int y = mouseY - 10;

        if (x + boxWidth - 20 >= this.width)
        {
            x -= boxWidth + 20;
        }

        int x1 = x + 10;
        int x2 = x1 + w1 + 20;

        RenderUtils.drawOutlinedBox(ctx, x, y, boxWidth, boxHeight, 0xFF000000, GuiBase.COLOR_HORIZONTAL_BAR);
        y += 6;
        int yIcon = y;
        y += 4;

        this.drawString(ctx, x1, y, 0xFFFFFFFF, hItem);
        this.drawString(ctx, x2 + 20, y, 0xFFFFFFFF, name);
        y += 16;
        this.drawString(ctx, x1, y, 0xFFFFFFFF, hTotal);
        this.drawString(ctx, x2, y, 0xFFFFFFFF, sTotal);
        y += 16;
        this.drawString(ctx, x1, y, 0xFFFFFFFF, hMissing);
        this.drawString(ctx, x2, y, 0xFFFFFFFF, sMissing);
        y += 16;
        this.drawString(ctx, x1, y, 0xFFFFFFFF, hAvail);
        this.drawString(ctx, x2, y, 0xFFFFFFFF, sAvail);

        RenderUtils.drawRect(ctx, x2, yIcon, 16, 16, 0x20FFFFFF);
        ctx.renderItem(stack, x2, yIcon);
    }

    /** 個数を「N = S x 64 + R = X.XX SB」形式 (スタック数 / シュルカー箱換算) に整形する。本家と同じ。 */
    private String getFormattedCountString(int total, int maxStackSize)
    {
        int stacks = total / maxStackSize;
        int remainder = total % maxStackSize;
        double boxCount = (double) total / (27D * maxStackSize);

        if (total > maxStackSize)
        {
            if (maxStackSize > 1)
            {
                if (remainder > 0)
                {
                    return String.format("%d = %d x %d + %d = %.2f %s", total, stacks, maxStackSize, remainder, boxCount, this.shulkerAbbr);
                }

                return String.format("%d = %d x %d = %.2f %s", total, stacks, maxStackSize, boxCount, this.shulkerAbbr);
            }

            return String.format("%d = %.2f %s", total, boxCount, this.shulkerAbbr);
        }

        return String.format("%d", total);
    }

    private record IgnoreListener(WidgetCraftTreeEntry widget, MatRow row) implements IButtonActionListener
    {
        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton)
        {
            this.widget.listWidget.getGui().ignoreRow(this.row);
        }
    }
}
