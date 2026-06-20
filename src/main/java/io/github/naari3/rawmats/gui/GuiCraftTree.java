package io.github.naari3.rawmats.gui;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;

import fi.dy.masa.malilib.gui.GuiListBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.util.KeyCodes;
import fi.dy.masa.malilib.util.StringUtils;

import io.github.naari3.rawmats.materials.CraftTree;
import io.github.naari3.rawmats.materials.MatRow;

public class GuiCraftTree extends GuiListBase<MatRow, WidgetCraftTreeEntry, WidgetListCraftTree>
{
    private final CraftTree tree;
    @Nullable private WidgetCraftMenu popup;

    public GuiCraftTree(CraftTree tree)
    {
        super(10, 46);

        this.tree = tree;
        this.title = StringUtils.translate("rawmats.gui.title.raw_material_list");
        this.useTitleHierarchy = false;
    }

    public CraftTree getTree()
    {
        return this.tree;
    }

    public void reInit()
    {
        this.initGui();
        if (this.getListWidget() != null)
        {
            this.getListWidget().refreshEntries();
        }
    }

    /** 左クリック: 展開 (展開は常に単一動作なので即実行)。 */
    public void expandRow(MatRow row)
    {
        this.tree.expand(row.item.value());
        this.reInit();
    }

    /** 右クリック: 親へ畳む。候補が 1 つなら即実行、複数のときだけアイコンポップアップで尋ねる。 */
    public void foldRow(MatRow row, int mouseX, int mouseY)
    {
        List<Item> candidates = this.tree.getCollapseCandidates(row.item.value());

        if (candidates.isEmpty())
        {
            return;
        }

        if (candidates.size() == 1)
        {
            this.tree.collapse(candidates.get(0));
            this.reInit();
            return;
        }

        List<WidgetCraftMenu.Entry> entries = new ArrayList<>();
        for (Item parent : candidates)
        {
            Holder<Item> holder = BuiltInRegistries.ITEM.wrapAsHolder(parent);
            entries.add(new WidgetCraftMenu.Entry(holder, () -> this.tree.collapse(parent)));
        }

        WidgetCraftMenu menu = new WidgetCraftMenu(mouseX, mouseY, entries, this);
        int px = Math.min(mouseX, this.getScreenWidth() - menu.getWidth() - 4);
        int py = Math.min(mouseY, this.getScreenHeight() - menu.getHeight() - 4);

        if (px != mouseX || py != mouseY)
        {
            menu = new WidgetCraftMenu(Math.max(2, px), Math.max(2, py), entries, this);
        }

        this.popup = menu;
    }

    public void onMenuActionDone()
    {
        this.popup = null;
        this.reInit();
    }

    @Override
    protected int getBrowserWidth()
    {
        return this.getScreenWidth() - 20;
    }

    @Override
    protected int getBrowserHeight()
    {
        return this.getScreenHeight() - 80;
    }

    @Override
    public void initGui()
    {
        super.initGui();
        this.popup = null;

        int x = 12;
        int y = 24;
        int gap = 2;

        x += this.addButton(x, y, ButtonType.COLLAPSE_ALL) + gap;
        x += this.addButton(x, y, ButtonType.EXPAND_ALL) + gap;
    }

    private int addButton(int x, int y, ButtonType type)
    {
        ButtonGeneric button = new ButtonGeneric(x, y, -1, 20, StringUtils.translate(type.key));
        this.addButton(button, new Listener(type, this));
        return button.getWidth();
    }

    @Override
    protected WidgetListCraftTree createListWidget(int listX, int listY)
    {
        return new WidgetListCraftTree(listX, listY, this.getBrowserWidth(), this.getBrowserHeight(), this);
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

        return super.onMouseClicked(click, doubleClick);
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
    public void drawContents(GuiContext ctx, int mouseX, int mouseY, float partialTicks)
    {
        super.drawContents(ctx, mouseX, mouseY, partialTicks);

        if (this.popup != null)
        {
            this.popup.render(ctx, mouseX, mouseY, false);
        }
    }

    private enum ButtonType
    {
        COLLAPSE_ALL ("rawmats.gui.button.collapse_all"),
        EXPAND_ALL   ("rawmats.gui.button.expand_all");

        private final String key;

        ButtonType(String key)
        {
            this.key = key;
        }
    }

    private record Listener(ButtonType type, GuiCraftTree parent) implements IButtonActionListener
    {
        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton)
        {
            switch (this.type)
            {
                case EXPAND_ALL   -> this.parent.tree.expandAll();
                case COLLAPSE_ALL -> this.parent.tree.collapseAll();
            }
            this.parent.reInit();
        }
    }
}
