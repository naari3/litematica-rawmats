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

/**
 * fold 先が複数のときだけ出す、アイコン式の一時ポップアップ。
 * 候補アイテムをアイコンで縦に並べ、ホバーで名前を右に表示。クリックで実行。
 */
public class WidgetCraftMenu extends WidgetContainer
{
    private static final int CELL = 20;
    private static final int PAD = 2;

    private final List<Entry> entries;
    private final GuiCraftTree gui;

    public WidgetCraftMenu(int x, int y, List<Entry> entries, GuiCraftTree gui)
    {
        super(x, y, CELL + PAD * 2, entries.size() * CELL + PAD * 2);

        this.entries = entries;
        this.gui = gui;
        this.setZLevel(50);
    }

    private int indexAt(int mouseX, int mouseY)
    {
        if (mouseX < this.x + PAD || mouseX > this.x + PAD + CELL)
        {
            return -1;
        }
        int rel = mouseY - (this.y + PAD);
        if (rel < 0)
        {
            return -1;
        }
        int i = rel / CELL;
        return (i >= 0 && i < this.entries.size()) ? i : -1;
    }

    @Override
    protected boolean onMouseClickedImpl(MouseButtonEvent click, boolean doubleClick)
    {
        int i = this.indexAt((int) click.x(), (int) click.y());
        if (i >= 0)
        {
            this.entries.get(i).action().run();
            this.gui.onMenuActionDone();
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
            int cx = this.x + PAD;
            int cy = this.y + PAD + i * CELL;

            if (i == hov)
            {
                RenderUtils.drawRect(ctx, cx, cy, CELL, CELL, 0xA0707070);
            }

            ItemStack stack = new ItemStack(this.entries.get(i).item());
            ctx.renderItem(stack, cx + 2, cy + 2);
        }

        // ホバー中のアイテム名を右側に
        if (hov >= 0)
        {
            String name = new ItemStack(this.entries.get(hov).item()).getHoverName().getString();
            int nx = this.x + this.width + 3;
            int ny = this.y + PAD + hov * CELL;
            int w = this.getStringWidth(name);
            RenderUtils.drawRect(ctx, nx - 2, ny, w + 4, CELL, 0xF0101010);
            this.drawString(ctx, nx, ny + 6, 0xFFFFFFFF, name);
        }
    }

    public record Entry(Holder<Item> item, Runnable action) { }
}
