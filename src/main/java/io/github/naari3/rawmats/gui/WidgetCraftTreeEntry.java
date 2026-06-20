package io.github.naari3.rawmats.gui;

import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.item.ItemStack;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.widgets.WidgetListEntryBase;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;

import io.github.naari3.rawmats.materials.MatRow;

/**
 * フラット買い物リストの 1 行。左クリックでポップアップメニュー (展開 / 親へ畳む) を開く。
 * マーカー: [+] 展開可 / [-] 畳み可 / [±] 両方。
 */
public class WidgetCraftTreeEntry extends WidgetListEntryBase<MatRow>
{
    private final MatRow row;
    private final WidgetListCraftTree listWidget;
    private final boolean isOdd;

    public WidgetCraftTreeEntry(int x, int y, int width, int height, boolean isOdd,
            MatRow row, int listIndex, WidgetListCraftTree listWidget)
    {
        super(x, y, width, height, row, listIndex);

        this.row = row;
        this.isOdd = isOdd;
        this.listWidget = listWidget;
    }

    @Override
    public boolean canSelectAt(MouseButtonEvent click)
    {
        return false;
    }

    @Override
    protected boolean onMouseClickedImpl(MouseButtonEvent click, boolean doubleClick)
    {
        if (this.row == null || !this.isMouseOver((int) click.x(), (int) click.y()))
        {
            return false;
        }

        // 左クリック = 展開、右クリック = 親へ畳む
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

        return false;
    }

    @Override
    public void render(GuiContext ctx, int mouseX, int mouseY, boolean selected)
    {
        if (this.isMouseOver(mouseX, mouseY))
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

        if (this.row == null)
        {
            return;
        }

        int textY = this.y + 5;

        // 操作マーカー
        String mark = this.row.expandable && this.row.foldable ? "[±]"
                : this.row.expandable ? "[+]"
                : this.row.foldable ? "[-]" : "";
        if (!mark.isEmpty())
        {
            this.drawString(ctx, this.x + 4, textY, 0xFFFFFFFF, mark);
        }

        // アイコン
        int iconX = this.x + 24;
        ItemStack stack = new ItemStack(this.row.item);
        RenderUtils.drawRect(ctx, iconX, this.y + 1, 16, 16, 0x20FFFFFF);
        ctx.renderItem(stack, iconX, this.y + 1);

        // 名前
        this.drawString(ctx, iconX + 20, textY, 0xFFFFFFFF, stack.getHoverName().getString());

        // 必要数 (在庫) — 右寄せ。充足(need 0)なら緑、不足なら金。
        String pre = this.row.need <= 0 ? GuiBase.TXT_GREEN : GuiBase.TXT_GOLD;
        String right = this.row.need + " (" + this.row.have + ")";
        int rx = this.x + this.width - this.getStringWidth(right) - 12;
        this.drawString(ctx, rx, textY, 0xFFFFFFFF, pre + right);
    }
}
