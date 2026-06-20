package io.github.naari3.rawmats.gui;

import java.util.Collection;
import javax.annotation.Nullable;

import fi.dy.masa.malilib.gui.widgets.WidgetListBase;

import io.github.naari3.rawmats.materials.CraftTree;
import io.github.naari3.rawmats.materials.MatRow;

public class WidgetListCraftTree extends WidgetListBase<MatRow, WidgetCraftTreeEntry>
{
    private final GuiCraftTree gui;

    public WidgetListCraftTree(int x, int y, int width, int height, GuiCraftTree gui)
    {
        super(x, y, width, height, null);

        this.gui = gui;
        this.browserEntryHeight = 18;
        this.shouldSortList = false;
    }

    public GuiCraftTree getGui()
    {
        return this.gui;
    }

    public CraftTree getTree()
    {
        return this.gui.getTree();
    }

    @Override
    protected Collection<MatRow> getAllEntries()
    {
        return this.gui.getTree().getDisplayRows();
    }

    @Override
    protected WidgetCraftTreeEntry createListEntryWidget(int x, int y, int listIndex, boolean isOdd, @Nullable MatRow entry)
    {
        return new WidgetCraftTreeEntry(x, y, this.browserEntryWidth, this.browserEntryHeight,
                isOdd, entry, listIndex, this);
    }
}
