package io.github.naari3.rawmats.gui;

import java.util.List;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.Holder;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.widgets.WidgetContainer;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.GuiUtils;

import io.github.naari3.rawmats.materials.Mat;

/**
 * fold 先・素材選択などの、アイコン式の一時ポップアップ。
 * 候補をアイコンで縦に並べ (画面に収まらなければ列を折り返す)、ホバーで名前を右に表示。クリックで実行。
 * 「任意」の候補はアイコンがメンバーを巡回する。現在の選択は金枠で示す。
 */
public class WidgetCraftMenu extends WidgetContainer
{
    private static final int CELL = 20;
    private static final int PAD = 2;
    private static final int COLOR_SELECTED = 0xFFE0B040;

    private final List<Entry> entries;
    /** エントリ実行後に呼ぶ (ポップアップを閉じて画面を更新する)。 */
    private final Runnable onDone;
    private final int rows;

    public WidgetCraftMenu(int x, int y, List<Entry> entries, Runnable onDone)
    {
        super(x, y, 0, 0);

        int maxRows = Math.max(1, (GuiUtils.getScaledWindowHeight() - 8 - PAD * 2) / CELL);
        this.rows = Math.max(1, Math.min(entries.size(), maxRows));
        int cols = Math.max(1, (entries.size() + this.rows - 1) / this.rows);
        this.setWidth(cols * CELL + PAD * 2);
        this.setHeight(this.rows * CELL + PAD * 2);

        this.entries = entries;
        this.onDone = onDone;
        this.setZLevel(50);
    }

    private int indexAt(int mouseX, int mouseY)
    {
        int relX = mouseX - (this.x + PAD);
        int relY = mouseY - (this.y + PAD);

        if (relX < 0 || relY < 0)
        {
            return -1;
        }

        int col = relX / CELL;
        int row = relY / CELL;

        if (row >= this.rows)
        {
            return -1;
        }

        int i = col * this.rows + row;
        return (i >= 0 && i < this.entries.size()) ? i : -1;
    }

    @Override
    protected boolean onMouseClickedImpl(MouseButtonEvent click, boolean doubleClick)
    {
        int i = this.indexAt((int) click.x(), (int) click.y());
        if (i >= 0)
        {
            this.entries.get(i).action().run();
            this.onDone.run();
            return true;
        }
        return false;
    }

    @Override
    public void render(GuiContext ctx, int mouseX, int mouseY, boolean selected)
    {
        RenderUtils.drawOutlinedBox(ctx, this.x, this.y, this.width, this.height, 0xF0101010, GuiBase.COLOR_HORIZONTAL_BAR);

        int hov = this.indexAt(mouseX, mouseY);

        for (int i = 0; i < this.entries.size(); i++)
        {
            int cx = this.x + PAD + (i / this.rows) * CELL;
            int cy = this.y + PAD + (i % this.rows) * CELL;
            Entry e = this.entries.get(i);

            if (i == hov)
            {
                RenderUtils.drawRect(ctx, cx, cy, CELL, CELL, 0xA0707070);
            }

            if (e.selected())
            {
                RenderUtils.drawOutlinedBox(ctx, cx, cy, CELL, CELL, 0x00000000, COLOR_SELECTED);
            }

            ctx.renderItem(new ItemStack(e.mat().icon()), cx + 2, cy + 2);
        }

        // ホバー中の名前を右側に (画面右端からはみ出すなら左側に反転)
        if (hov >= 0)
        {
            String name = this.entries.get(hov).label();
            int w = this.getStringWidth(name);
            int nx = this.x + this.width + 3;

            if (nx + w + 4 > GuiUtils.getScaledWindowWidth())
            {
                nx = this.x - w - 5;
            }

            int ny = this.y + PAD + (hov % this.rows) * CELL;
            RenderUtils.drawRect(ctx, nx - 2, ny, w + 4, CELL, 0xF0101010);
            this.drawString(ctx, nx, ny + 6, 0xFFFFFFFF, name);
        }
    }

    /**
     * @param mat      アイコンにする単位 (「任意」なら巡回表示)
     * @param label    ホバー時に出す名前
     * @param selected 現在の選択か (金枠)
     */
    public record Entry(Mat mat, String label, boolean selected, Runnable action)
    {
        public Entry(Holder<Item> item, Runnable action)
        {
            this(Mat.of(item), new ItemStack(item).getHoverName().getString(), false, action);
        }

        public Entry(Mat mat, Runnable action)
        {
            this(mat, mat.displayName(), false, action);
        }
    }
}
