package io.github.naari3.rawmats.gui;

import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.tags.TagKey;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.display.FurnaceRecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.StonecutterRecipeDisplay;
import org.apache.commons.lang3.tuple.Pair;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.mixin.recipe.IMixinIngredient;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.StringUtils;
import fi.dy.masa.malilib.util.game.RecipeBookUtils;

import io.github.naari3.rawmats.materials.CraftTree;

/**
 * ホバー行のクラフトレシピを描く小型ツールチップ (Tab 長押しで表示)。
 *
 * レシピは client recipe book から引く ({@link RecipeBookUtils})。選択優先度はツリーの分解
 * ({@code CraftNode#chosenEntry}) と同じ crafting > stonecutter > furnace。
 * 染め直し系 (bed + dye 等、出力自身が材料候補に含まれるレシピ) は代替があればそちらを使う
 * (Litematica の {@code MaterialListJsonOverrides#overrideShouldSkipRecipe} の汎用版)。
 */
public class RecipeTooltip
{
    /** 1 マスのピッチ (アイコン 16px + 隙間 2px)。 */
    private static final int SLOT = 18;
    private static final int PAD = 6;
    private static final String ARROW = "->";

    private final RecipeDisplay display;
    private final String typeLabelKey;
    private final int gridWidth;
    private final int gridHeight;
    private final List<SlotDisplay> gridSlots;

    private RecipeTooltip(RecipeDisplay display)
    {
        this.display = display;

        switch (display)
        {
            case ShapedCraftingRecipeDisplay d ->
            {
                this.gridWidth = Math.max(1, d.width());
                this.gridHeight = Math.max(1, d.height());
                this.gridSlots = d.ingredients();
                this.typeLabelKey = "rawmats.gui.label.recipe.crafting";
            }
            case ShapelessCraftingRecipeDisplay d ->
            {
                int n = Math.max(1, d.ingredients().size());
                this.gridWidth = Math.min(n, 3);
                this.gridHeight = (n + 2) / 3;
                this.gridSlots = d.ingredients();
                this.typeLabelKey = "rawmats.gui.label.recipe.crafting";
            }
            case StonecutterRecipeDisplay d ->
            {
                this.gridWidth = 1;
                this.gridHeight = 1;
                this.gridSlots = List.of(d.input());
                this.typeLabelKey = "rawmats.gui.label.recipe.stonecutter";
            }
            case FurnaceRecipeDisplay d ->
            {
                this.gridWidth = 1;
                this.gridHeight = 1;
                this.gridSlots = List.of(d.ingredient());
                this.typeLabelKey = "rawmats.gui.label.recipe.smelting";
            }
            default ->
            {
                // lookup で crafting/stonecutter/furnace のみ通すので来ない想定。
                this.gridWidth = 1;
                this.gridHeight = 1;
                this.gridSlots = List.of();
                this.typeLabelKey = "rawmats.gui.label.recipe.crafting";
            }
        }
    }

    /**
     * item を出力するレシピを client recipe book から探す。無ければ null (未開放レシピも出ない)。
     * 優先度はツリーの分解と同じ crafting > stonecutter > furnace。
     */
    @Nullable
    public static RecipeTooltip lookup(Holder<Item> item)
    {
        ItemStack stack = new ItemStack(item);
        List<Pair<RecipeDisplayId, RecipeDisplayEntry>> lookup = RecipeBookUtils.getDisplayEntryFromRecipeBook(
                stack, List.of(RecipeBookUtils.Type.SHAPED, RecipeBookUtils.Type.SHAPELESS));

        if (lookup != null && lookup.isEmpty())
        {
            lookup = RecipeBookUtils.getDisplayEntryFromRecipeBook(stack, List.of(RecipeBookUtils.Type.STONECUTTER));
        }

        if (lookup != null && lookup.isEmpty())
        {
            lookup = RecipeBookUtils.getDisplayEntryFromRecipeBook(stack, List.of(RecipeBookUtils.Type.FURNACE));
        }

        if (lookup == null || lookup.isEmpty())
        {
            return null;
        }

        // 出力自身が材料候補に含まれるレシピ (染め直し等) は避け、代替が無ければ先頭のまま。
        RecipeDisplayEntry chosen = null;

        for (Pair<RecipeDisplayId, RecipeDisplayEntry> pair : lookup)
        {
            if (!isSelfReferential(pair.getRight(), item))
            {
                chosen = pair.getRight();
                break;
            }
        }

        if (chosen == null)
        {
            chosen = lookup.getFirst().getRight();
        }

        return new RecipeTooltip(chosen.display());
    }

    /** 材料グリッド + 矢印 + 完成品を、既存ホバーボックスと同じ流儀 (mouse 追従・右端で左反転) で描く。 */
    public void render(GuiContext ctx, int mouseX, int mouseY, int clampWidth, CraftTree tree)
    {
        Minecraft mc = Minecraft.getInstance();
        ContextMap map = RecipeBookUtils.getMap(mc);

        if (map == null)
        {
            return;
        }

        String label = StringUtils.translate(this.typeLabelKey);
        int arrowWidth = StringUtils.getStringWidth(ARROW);
        int gridPixelW = this.gridWidth * SLOT;
        int gridPixelH = this.gridHeight * SLOT;
        int bodyWidth = gridPixelW + 8 + arrowWidth + 8 + SLOT;
        int boxWidth = Math.max(bodyWidth, StringUtils.getStringWidth(label)) + PAD * 2;
        int boxHeight = PAD + 12 + gridPixelH + PAD;

        int x = mouseX + 10;
        int y = mouseY - 10;

        if (x + boxWidth - 20 >= clampWidth)
        {
            x -= boxWidth + 20;
        }

        RenderUtils.drawOutlinedBox(ctx, x, y, boxWidth, boxHeight, 0xFF000000, GuiBase.COLOR_HORIZONTAL_BAR);
        ctx.drawString(mc.font, label, x + PAD, y + PAD, 0xFFC0C0C0);

        int gx = x + PAD;
        int gy = y + PAD + 12;

        for (int row = 0; row < this.gridHeight; row++)
        {
            for (int col = 0; col < this.gridWidth; col++)
            {
                int cx = gx + col * SLOT;
                int cy = gy + row * SLOT;
                RenderUtils.drawRect(ctx, cx, cy, 16, 16, 0x20FFFFFF);

                int i = row * this.gridWidth + col;

                if (i >= this.gridSlots.size())
                {
                    continue;
                }

                ItemStack shown = resolveSlot(this.gridSlots.get(i), map, tree);

                if (!shown.isEmpty())
                {
                    ctx.renderItem(shown, cx, cy);
                }
            }
        }

        int midY = gy + gridPixelH / 2;
        ctx.drawString(mc.font, ARROW, gx + gridPixelW + 8, midY - 4, 0xFFFFFFFF);

        int rx = gx + gridPixelW + 8 + arrowWidth + 8;
        int ry = midY - 8;
        RenderUtils.drawRect(ctx, rx, ry, 16, 16, 0x20FFFFFF);
        ItemStack result = this.display.result().resolveForFirstStack(map);

        if (!result.isEmpty())
        {
            ctx.renderItem(result, rx, ry);
            ctx.renderItemDecorations(mc.font, result, rx, ry);
        }
    }

    /** タグ材料はユーザー置換 (中クリック選択) があればそれを固定表示、無ければ候補を1秒周期で巡回表示。 */
    private static ItemStack resolveSlot(SlotDisplay slot, ContextMap map, CraftTree tree)
    {
        if (slot instanceof SlotDisplay.TagSlotDisplay tag)
        {
            Holder<Item> override = tree.getMaterialOverride(tagKeyOf(tag.tag()));

            if (override != null)
            {
                return new ItemStack(override);
            }
        }

        List<ItemStack> stacks = slot.resolveForStacks(map);

        if (stacks.isEmpty())
        {
            return ItemStack.EMPTY;
        }

        if (stacks.size() == 1)
        {
            return stacks.getFirst();
        }

        int idx = (int) ((System.currentTimeMillis() / 1000L) % stacks.size());
        return stacks.get(idx);
    }

    /**
     * {@link SlotDisplay.TagSlotDisplay#tag()} のタグキー。版によって TagKey (〜26.2) か
     * HolderSet (26.3〜) を返すので両方を受ける。
     */
    @Nullable
    @SuppressWarnings("unchecked")
    private static TagKey<Item> tagKeyOf(Object tag)
    {
        if (tag instanceof TagKey<?> key)
        {
            return (TagKey<Item>) key;
        }

        if (tag instanceof HolderSet<?> set)
        {
            return (TagKey<Item>) set.unwrapKey().orElse(null);
        }

        return null;
    }

    /** 出力アイテム自身が材料候補に含まれるか (bed + dye のような染め直しレシピの検出)。 */
    private static boolean isSelfReferential(RecipeDisplayEntry entry, Holder<Item> output)
    {
        if (entry.craftingRequirements().isEmpty())
        {
            return false;
        }

        for (Ingredient ing : entry.craftingRequirements().get())
        {
            HolderSet<Item> entries = ((IMixinIngredient) (Object) ing).malilib_getEntries();

            for (Holder<Item> h : entries)
            {
                if (h.value() == output.value())
                {
                    return true;
                }
            }
        }

        return false;
    }
}
