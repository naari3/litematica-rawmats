package io.github.naari3.rawmats.materials;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;

import fi.dy.masa.malilib.mixin.recipe.IMixinIngredient;
import fi.dy.masa.litematica.materials.json.MaterialListJsonBase;
import fi.dy.masa.litematica.materials.json.MaterialListJsonEntry;

/**
 * クラフトツリーの 1 ノード。Litematica の {@link MaterialListJsonBase}(再帰展開済みツリー)を
 * 表示用にラップする。子 = 選ばれたレシピの材料。
 *
 * 合算 (merge) ビューでは、underlying base を持たない合成ノードとしても使う。
 */
public class CraftNode
{
    public final Holder<Item> item;
    public final int count;
    public final int depth;
    public final List<CraftNode> children = new ArrayList<>();
    public boolean expanded = false;

    /** タグ材料 (any planks 等) 由来のとき、その置換キー (タグ)。具体材料由来なら null。 */
    @Nullable public TagKey<Item> choiceKey;
    /** タグ材料の候補一覧 (選択肢)。具体材料由来なら null。 */
    @Nullable public List<Holder<Item>> choices;

    public CraftNode(Holder<Item> item, int count, int depth)
    {
        this.item = item;
        this.count = count;
        this.depth = depth;
    }

    public boolean isExpandable()
    {
        return !this.children.isEmpty();
    }

    /** この材料がタグ由来で、別の具体素材に置換できるか。 */
    public boolean isChoosable()
    {
        return this.choiceKey != null && this.choices != null && this.choices.size() > 1;
    }

    public void toggle()
    {
        if (this.isExpandable())
        {
            this.expanded = !this.expanded;
        }
    }

    /**
     * {@link MaterialListJsonBase} ツリーから CraftNode ツリーを構築する。
     * MaterialListJsonBase の構築時点で全再帰展開は済んでいる (loop 検出込み) ので、基本は写すだけ。
     *
     * タグ材料 (any planks 等、候補が複数ある ingredient) の子については、
     * - 元 {@link Ingredient} の候補 ({@link IMixinIngredient#malilib_getEntries}) を保持し ({@link #choices}/{@link #choiceKey})、
     * - {@code overrides} にユーザー選択があれば、その具体素材でサブツリーを作り直す。
     * 具体材料 (候補 1 つ = birch_planks 固定要求等) は対象外で、Litematica の選択どおりに固定。
     */
    public static CraftNode fromBase(MaterialListJsonBase base, int depth,
            Map<TagKey<Item>, Holder<Item>> overrides, boolean craftingOnly)
    {
        CraftNode node = new CraftNode(base.getInput(), base.getCount(), depth);
        MaterialListJsonEntry chosen = chosenEntry(base);

        if (chosen != null)
        {
            List<Ingredient> ingredients = null;

            if (chosen.getRecipeRequirements() != null && chosen.getPrimaryId() != null)
            {
                ingredients = chosen.getRecipeRequirements().get(chosen.getPrimaryId());
            }

            for (MaterialListJsonBase child : chosen.getRequirements())
            {
                Ingredient tagIng = ingredients != null ? findTagIngredient(ingredients, child.getInput()) : null;
                TagKey<Item> key = tagIng != null
                        ? ((IMixinIngredient) (Object) tagIng).malilib_getEntries().unwrapKey().orElse(null)
                        : null;

                if (key != null)
                {
                    Holder<Item> override = overrides.get(key);
                    MaterialListJsonBase effChild = child;

                    if (override != null && override.value() != child.getInput().value())
                    {
                        // ユーザーが別素材を選択 → サブツリーをその素材で作り直す (count は同種レシピ前提で維持)。
                        effChild = new MaterialListJsonBase(override, child.getCount(), base.getInput(), craftingOnly);
                    }

                    CraftNode cn = fromBase(effChild, depth + 1, overrides, craftingOnly);
                    cn.choiceKey = key;
                    cn.choices = ((IMixinIngredient) (Object) tagIng).malilib_getEntries().stream().toList();
                    node.children.add(cn);
                }
                else
                {
                    node.children.add(fromBase(child, depth + 1, overrides, craftingOnly));
                }
            }
        }

        return node;
    }

    /** ingredients のうち「候補が複数あり (= タグ等)、resolvedItem を候補に含む」最初の ingredient を返す。 */
    @Nullable
    private static Ingredient findTagIngredient(List<Ingredient> ingredients, Holder<Item> resolvedItem)
    {
        for (Ingredient ing : ingredients)
        {
            HolderSet<Item> entries = ((IMixinIngredient) (Object) ing).malilib_getEntries();

            if (entries.size() > 1 && contains(entries, resolvedItem))
            {
                return ing;
            }
        }

        return null;
    }

    private static boolean contains(HolderSet<Item> entries, Holder<Item> item)
    {
        for (Holder<Item> h : entries)
        {
            if (h.value() == item.value())
            {
                return true;
            }
        }

        return false;
    }

    /**
     * base が選択したレシピ経路 (crafting > stonecutter > furnace) を返す。
     * remaining (= leaf / レシピ無し) は子を持たないので null 扱い。
     */
    private static MaterialListJsonEntry chosenEntry(MaterialListJsonBase b)
    {
        if (b.getMaterialsCrafting() != null)    return b.getMaterialsCrafting();
        if (b.getMaterialsStonecutter() != null) return b.getMaterialsStonecutter();
        if (b.getMaterialsFurnace() != null)     return b.getMaterialsFurnace();
        return null;
    }
}
