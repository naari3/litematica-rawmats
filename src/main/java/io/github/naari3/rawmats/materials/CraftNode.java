package io.github.naari3.rawmats.materials;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
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

    /** タグ材料 (any planks 等) 由来のとき、そのスロットが受け付ける集合。具体材料由来なら null。 */
    @Nullable public Mat.AnyOf choice;
    /** この需要を「任意」として数えるとき、その集合 ({@link CraftTree#applyChoices} が付ける)。具体なら null。 */
    @Nullable public Mat.AnyOf any;
    /**
     * 親がタグスロットで具体素材に指定されているとき、その親のタグ (例: crimson_planks を選んだ #planks)。
     * 展開先 (crimson_stem) の行から親の選択を変えるのに使う。{@link #upstreamSlot} は親の分解での子の位置。
     */
    @Nullable public Mat.AnyOf upstream;
    public int upstreamSlot = -1;
    /** 親アイテム (部分在庫スケーリングの再 build で prevItem = ループ検出に使う)。root なら null。 */
    @Nullable public Holder<Item> parentItem;

    public CraftNode(Holder<Item> item, int count, int depth)
    {
        this.item = item;
        this.count = count;
        this.depth = depth;
    }

    /** 集計の単位 (「任意」なら {@link #any}、そうでなければ具体アイテム)。 */
    public Mat mat()
    {
        return this.any != null ? this.any : Mat.of(this.item);
    }

    public boolean isExpandable()
    {
        return !this.children.isEmpty();
    }

    /** この材料がタグ由来で、別の具体素材や「任意」に切り替えられるか。 */
    public boolean isChoosable()
    {
        return (this.choice != null && this.choice.size() > 1) || this.upstream != null;
    }

    /**
     * {@link MaterialListJsonBase} ツリーから CraftNode ツリーを構築する。
     * MaterialListJsonBase の構築時点で全再帰展開は済んでいる (loop 検出込み) ので、基本は写すだけ。
     *
     * タグ材料 (any planks 等、候補が複数ある ingredient) の子には、元 {@link Ingredient} の候補
     * ({@link IMixinIngredient#malilib_getEntries}) を {@link #choice} として付ける。
     * 「任意」化とユーザー選択の適用は {@link CraftTree#applyChoices} が行う (テストの fake 分解と同じ経路にするため)。
     */
    public static CraftNode fromBase(MaterialListJsonBase base, int depth)
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
                CraftNode cn = fromBase(child, depth + 1);
                cn.parentItem = base.getInput();
                Ingredient tagIng = ingredients != null ? findTagIngredient(ingredients, child.getInput()) : null;

                if (tagIng != null)
                {
                    HolderSet<Item> entries = ((IMixinIngredient) (Object) tagIng).malilib_getEntries();
                    cn.choice = new Mat.AnyOf(entries.unwrapKey().orElse(null), entries.stream().toList(), child.getInput());
                }

                node.children.add(cn);
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
