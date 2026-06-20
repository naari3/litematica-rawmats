package io.github.naari3.rawmats.materials;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.world.item.Item;

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

    public void toggle()
    {
        if (this.isExpandable())
        {
            this.expanded = !this.expanded;
        }
    }

    /**
     * {@link MaterialListJsonBase} ツリーから CraftNode ツリーを構築する。
     * MaterialListJsonBase の構築時点で全再帰展開は済んでいる (loop 検出込み) ので、ここでは写すだけ。
     */
    public static CraftNode fromBase(MaterialListJsonBase base, int depth)
    {
        CraftNode node = new CraftNode(base.getInput(), base.getCount(), depth);
        MaterialListJsonEntry chosen = chosenEntry(base);

        if (chosen != null)
        {
            for (MaterialListJsonBase child : chosen.getRequirements())
            {
                node.children.add(fromBase(child, depth + 1));
            }
        }

        return node;
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
