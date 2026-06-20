package io.github.naari3.rawmats.gui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.annotation.Nullable;
import com.google.common.collect.ImmutableList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import fi.dy.masa.malilib.gui.LeftRight;
import fi.dy.masa.malilib.gui.widgets.WidgetListBase;
import fi.dy.masa.malilib.gui.widgets.WidgetSearchBar;
import fi.dy.masa.litematica.gui.Icons;

import io.github.naari3.rawmats.materials.CraftTree;
import io.github.naari3.rawmats.materials.MatRow;

public class WidgetListCraftTree extends WidgetListBase<MatRow, WidgetCraftTreeEntry>
{
    private final GuiCraftTree gui;

    public WidgetListCraftTree(int x, int y, int width, int height, GuiCraftTree gui)
    {
        super(x, y, width, height, null);

        this.gui = gui;
        this.browserEntryHeight = 22;
        this.shouldSortList = false;
        this.widgetSearchBar = new WidgetSearchBar(x + 2, y + 8, width - 16, 14, 0, Icons.FILE_ICON_SEARCH, LeftRight.RIGHT);
        this.widgetSearchBar.setZLevel(1);
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
        List<MatRow> rows = new ArrayList<>(this.gui.getTree().getDisplayRows());
        // 列幅をウィジェット生成前に確定させる。
        WidgetCraftTreeEntry.setMaxLengths(rows);
        return rows;
    }

    /** 本家同様、スクロールしない列ヘッダ行 (row == null) を出す。 */
    @Override
    protected WidgetCraftTreeEntry createHeaderWidget(int x, int y, int listIndexStart, int usableHeight, int usedHeight)
    {
        if ((usedHeight + this.browserEntryHeight) > usableHeight)
        {
            return null;
        }

        return this.createListEntryWidget(x, y, listIndexStart, true, null);
    }

    @Override
    protected WidgetCraftTreeEntry createListEntryWidget(int x, int y, int listIndex, boolean isOdd, @Nullable MatRow entry)
    {
        return new WidgetCraftTreeEntry(x, y, this.browserEntryWidth, this.browserEntryHeight,
                isOdd, entry, listIndex, this);
    }

    /** 検索フィルタ対象 = アイテム表示名 + 登録 ID (本家 WidgetListMaterialList に倣う)。 */
    @Override
    protected List<String> getEntryStringsForFilter(MatRow entry)
    {
        ItemStack stack = new ItemStack(entry.item);
        Identifier rl = BuiltInRegistries.ITEM.getKey(stack.getItem());
        String name = stack.getHoverName().getString().toLowerCase();

        if (rl != null)
        {
            return ImmutableList.of(name, rl.toString().toLowerCase());
        }

        return ImmutableList.of(name);
    }
}
