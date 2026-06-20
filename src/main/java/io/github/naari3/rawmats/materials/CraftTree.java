package io.github.naari3.rawmats.materials;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;

import fi.dy.masa.malilib.util.data.ItemType;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.materials.MaterialListUtils;
import fi.dy.masa.litematica.materials.json.MaterialListJsonBase;

/**
 * 「編集可能なフラット買い物リスト」のモデル。
 *
 * - root = schematic 直材。各々を {@link MaterialListJsonBase} で再帰展開した baked ツリー ({@link CraftNode}) を保持。
 * - 表示状態 = 「展開したアイテム種別」の集合 ({@link #expanded})。
 * - 表示 ({@link #getDisplayRows}) = baked ツリーを辿り、展開対象は子へ in-place 置換、
 *   それ以外は frontier 葉として同一アイテムで合算 → フラットなリスト ({@link MatRow})。
 * - 在庫ネット: walk 中に在庫を引き当て、完全充足ノードは展開停止 (= 子を出さない)。
 * - 畳み (collapse): frontier 素材 I は「I を直接の子に持つ展開中の親 P」由来。collapse(P) で P を畳み戻す。
 *   I を畳む候補 = {@link #getCollapseCandidates}。
 */
public class CraftTree
{
    private final List<CraftNode> roots = new ArrayList<>();
    private final Set<Item> expanded = new LinkedHashSet<>();
    private final Set<Item> expandableTypes = new HashSet<>();
    /** 子アイテム種別 -> それを直接の子に持つ親アイテム種別の集合 (畳み候補の逆引き)。 */
    private final Map<Item, Set<Item>> childToParents = new HashMap<>();
    private final Object2IntOpenHashMap<Item> available = new Object2IntOpenHashMap<>();

    /** crafting と stonecutter が両立する場合に crafting を優先。 */
    private boolean craftingOnly = true;

    public CraftTree(MaterialListBase source)
    {
        this.rebuild(source);
    }

    public void rebuild(MaterialListBase source)
    {
        this.roots.clear();
        this.expandableTypes.clear();
        this.childToParents.clear();

        for (MaterialListEntry e : source.getMaterialsAll())
        {
            Holder<Item> item = e.getStack().typeHolder();
            int total = e.getStack().getCount() * e.getCountTotal();
            MaterialListJsonBase base = new MaterialListJsonBase(item, total, null, this.craftingOnly);
            CraftNode node = CraftNode.fromBase(base, 0);
            this.roots.add(node);
            this.indexNode(node);
        }

        this.refreshAvailable();
    }

    private void indexNode(CraftNode n)
    {
        if (n.isExpandable())
        {
            this.expandableTypes.add(n.item.value());

            for (CraftNode c : n.children)
            {
                this.childToParents.computeIfAbsent(c.item.value(), k -> new LinkedHashSet<>()).add(n.item.value());
            }
        }
        for (CraftNode c : n.children)
        {
            this.indexNode(c);
        }
    }

    public void refreshAvailable()
    {
        this.available.clear();
        Player player = Minecraft.getInstance().player;

        if (player == null)
        {
            return;
        }

        Object2IntOpenHashMap<ItemType> inv = MaterialListUtils.getInventoryItemCounts(player.getInventory());

        for (Object2IntMap.Entry<ItemType> en : inv.object2IntEntrySet())
        {
            this.available.addTo(en.getKey().getStack().getItem(), en.getIntValue());
        }
    }

    public int availableFor(Holder<Item> item) { return this.available.getInt(item.value()); }

    public boolean isExpandable(Item it) { return this.expandableTypes.contains(it) && !this.expanded.contains(it); }

    public void expand(Item it)   { if (this.expandableTypes.contains(it)) this.expanded.add(it); }
    public void collapse(Item it) { this.expanded.remove(it); }
    public void expandAll()       { this.expanded.addAll(this.expandableTypes); }
    public void collapseAll()     { this.expanded.clear(); }

    /** frontier 素材 child を畳み戻せる「展開中の親」候補 (= child を直接の子に持つ、展開中の親種別)。 */
    public List<Item> getCollapseCandidates(Item child)
    {
        List<Item> out = new ArrayList<>();
        Set<Item> parents = this.childToParents.get(child);

        if (parents != null)
        {
            for (Item it : this.expanded)
            {
                if (parents.contains(it))
                {
                    out.add(it);
                }
            }
        }
        return out;
    }

    public boolean hasCollapseCandidate(Item child)
    {
        Set<Item> parents = this.childToParents.get(child);
        if (parents == null)
        {
            return false;
        }
        for (Item it : parents)
        {
            if (this.expanded.contains(it))
            {
                return true;
            }
        }
        return false;
    }

    /** 現在の展開状態 + 在庫ネットで、フラットな表示行を計算する。 */
    public List<MatRow> getDisplayRows()
    {
        Object2IntOpenHashMap<Item> budget = new Object2IntOpenHashMap<>(this.available);
        LinkedHashMap<Item, int[]> needMap = new LinkedHashMap<>();

        for (CraftNode r : this.roots)
        {
            this.walk(r, budget, needMap);
        }

        List<MatRow> rows = new ArrayList<>();
        for (Map.Entry<Item, int[]> en : needMap.entrySet())
        {
            Item it = en.getKey();
            Holder<Item> holder = BuiltInRegistries.ITEM.wrapAsHolder(it);
            boolean expandable = this.expandableTypes.contains(it) && !this.expanded.contains(it);
            boolean foldable = this.hasCollapseCandidate(it);
            rows.add(new MatRow(holder, en.getValue()[0], this.available.getInt(it), expandable, foldable));
        }
        return rows;
    }

    private void walk(CraftNode n, Object2IntOpenHashMap<Item> budget, Map<Item, int[]> needMap)
    {
        Item it = n.item.value();
        int alloc = Math.min(n.count, budget.getInt(it));
        budget.addTo(it, -alloc);
        int net = n.count - alloc;
        boolean covered = net == 0;

        if (!covered && this.expanded.contains(it) && n.isExpandable())
        {
            for (CraftNode c : n.children)
            {
                this.walk(c, budget, needMap);
            }
        }
        else
        {
            needMap.computeIfAbsent(it, k -> new int[1])[0] += net;
        }
    }
}
