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
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import fi.dy.masa.malilib.util.data.ItemType;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.materials.MaterialListUtils;
import fi.dy.masa.litematica.materials.json.MaterialListJsonBase;

import io.github.naari3.rawmats.Reference;

/**
 * 「編集可能なフラット買い物リスト」のモデル。
 *
 * - root = schematic 直材。各々を {@link MaterialListJsonBase} で再帰展開した baked ツリー ({@link CraftNode}) を保持。
 * - 表示状態 = 「展開したアイテム種別」の集合ほか ({@link CraftTreeState})。source から分離して保持。
 * - 表示 ({@link #getDisplayRows}) = 展開対象の型を親→子 topo 順で処理し、各型の総需要を1回だけ
 *   在庫ネット + 分解 → frontier (集める単位) を同一アイテムで合算したフラットリスト ({@link MatRow})。
 * - 在庫ネットは型ごとにグローバル (DFS 到達順に非依存)。中間素材のバッチ切り上げは総量に対して1回
 *   (出現ごとの過大計上を回避)。完全充足の展開対象は展開停止 (covered)。
 * - 畳み (collapse): frontier 素材 I は「I を直接の子に持つ展開中の親 P」由来。collapse(P) で P を畳み戻す。
 *   I を畳む候補 = {@link #getCollapseCandidates}。
 */
public class CraftTree
{
    private final List<CraftNode> roots = new ArrayList<>();
    private final Set<Item> expandableTypes = new HashSet<>();
    /** 子アイテム種別 -> それを直接の子に持つ親アイテム種別の集合 (畳み候補の逆引き)。 */
    private final Map<Item, Set<Item>> childToParents = new HashMap<>();
    /** 親アイテム種別 -> その直接の子アイテム種別の集合 (展開対象を topo 順に処理するための型 DAG)。 */
    private final Map<Item, Set<Item>> typeChildren = new HashMap<>();
    private final Object2IntOpenHashMap<Item> available = new Object2IntOpenHashMap<>();
    /** セッション内ビュー状態 (展開集合 / タグ置換 / 倍率)。source キーごとに {@link CraftTreeStore} で復元される。 */
    private final CraftTreeState state;
    /** 直近の source。タグ材料の置換時にツリーを作り直すため保持。 */
    private MaterialListBase source;

    /** crafting と stonecutter が両立する場合に crafting を優先。 */
    private boolean craftingOnly = true;

    /** {@link #getDisplayRows} のキャッシュ。状態変化 (展開/在庫/ソート/hide/ignore/倍率/タグ) 時のみ再計算する。 */
    private List<MatRow> cachedRows;
    private boolean rowsDirty = true;

    /** 表示の並べ替え列 (本家 GuiMaterialList と同じ4列)。状態は {@link CraftTreeState} 側でセッション保持。 */
    public enum SortColumn { NAME, TOTAL, MISSING, AVAILABLE }

    public CraftTree(MaterialListBase source, CraftTreeState state)
    {
        this.state = state;
        this.rebuild(source);
    }

    public void rebuild(MaterialListBase source)
    {
        this.source = source;
        this.roots.clear();
        this.expandableTypes.clear();
        this.childToParents.clear();
        this.typeChildren.clear();

        int mult = Math.max(1, this.state.multiplier);

        for (MaterialListEntry e : source.getMaterialsAll())
        {
            Holder<Item> item = e.getStack().typeHolder();
            int total = e.getStack().getCount() * e.getCountTotal() * mult;
            MaterialListJsonBase base = new MaterialListJsonBase(item, total, null, this.craftingOnly);
            CraftNode node = CraftNode.fromBase(base, 0, this.state.materialOverride, this.craftingOnly);
            this.roots.add(node);
            this.indexNode(node);
        }

        this.refreshAvailable();
        this.rowsDirty = true;
    }

    public int getMultiplier() { return this.state.multiplier; }

    /** 書き出し用: source の表示タイトル。 */
    public String getSourceTitle() { return this.source != null ? this.source.getTitle() : ""; }

    /** 書き出し用: タグ材料の置換選択 (タグ -> 具体素材)。ユーザーが選んだものだけ含む。 */
    public Map<TagKey<Item>, Holder<Item>> getMaterialOverrides() { return this.state.materialOverride; }

    /** 倍率を設定し (1 以上にクランプ)、変化があればツリーを作り直す (展開状態・タグ選択は維持)。 */
    public void setMultiplier(int multiplier)
    {
        int clamped = Math.max(1, multiplier);

        if (clamped != this.state.multiplier)
        {
            this.state.multiplier = clamped;

            if (this.source != null)
            {
                this.rebuild(this.source);
            }
        }
    }

    /** タグ材料 key に具体素材 item を割り当て、ツリーを再構築する (展開状態は維持)。 */
    public void chooseMaterial(TagKey<Item> key, Holder<Item> item)
    {
        this.state.materialOverride.put(key, item);

        if (this.source != null)
        {
            this.rebuild(this.source);
        }
    }

    private void indexNode(CraftNode n)
    {
        if (n.isExpandable())
        {
            Item parent = n.item.value();
            this.expandableTypes.add(parent);
            Set<Item> kids = this.typeChildren.computeIfAbsent(parent, k -> new LinkedHashSet<>());

            for (CraftNode c : n.children)
            {
                Item child = c.item.value();
                this.childToParents.computeIfAbsent(child, k -> new LinkedHashSet<>()).add(parent);
                kids.add(child);
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

    /**
     * デバッグ用: ベイク済みツリー構造 + フラット表示行をログに出す (Expand all 押下時に呼ばれる)。
     * 各ノードの item / count / 子数 / 展開可否、leaf については recipe book を直接引いて
     * 「レシピが book に存在するか」を出す (recipeBookLookup: -1=level/player null, 0=book に無い)。
     * 在庫差引後の表示行 (need/have/expandable/foldable/choosable) も合わせて確認できる。
     */
    public void logStructure()
    {
        Reference.LOGGER.info("[rawmats] CraftTree dump: roots={}, expandableTypes={}, multiplier={}",
                this.roots.size(), this.expandableTypes.size(), this.state.multiplier);

        for (CraftNode r : this.roots)
        {
            this.dumpNode(r);
        }

        // 実際にユーザーが見るフラット表示行もダンプする (在庫差引・合算後の状態)。
        List<MatRow> rows = this.getDisplayRows();
        Reference.LOGGER.info("[rawmats] display rows ({}) ====", rows.size());

        for (MatRow r : rows)
        {
            Reference.LOGGER.info("[rawmats]   ROW {} need={} have={} expandable={} foldable={} choosable={}",
                    BuiltInRegistries.ITEM.getKey(r.item.value()), r.need, r.have, r.expandable, r.foldable, r.choosable);
        }
    }

    private void dumpNode(CraftNode n)
    {
        String id = BuiltInRegistries.ITEM.getKey(n.item.value()).toString();
        String indent = "  ".repeat(n.depth);
        Reference.LOGGER.info("[rawmats] {}{} x{} children={} expandable={} choosable={}",
                indent, id, n.count, n.children.size(), n.isExpandable(), n.isChoosable());

        for (CraftNode c : n.children)
        {
            this.dumpNode(c);
        }
    }

    public boolean isExpandable(Item it) { return this.expandableTypes.contains(it) && !this.state.expanded.contains(it); }

    public void expand(Item it)   { if (this.expandableTypes.contains(it)) { this.state.expanded.add(it); this.rowsDirty = true; } }
    public void collapse(Item it) { this.state.expanded.remove(it); this.rowsDirty = true; }
    public void expandAll()       { this.state.expanded.addAll(this.expandableTypes); this.rowsDirty = true; }
    public void collapseAll()     { this.state.expanded.clear(); this.rowsDirty = true; }

    public SortColumn getSortColumn()   { return this.state.sortColumn; }
    public boolean getSortInReverse()   { return this.state.sortReverse; }
    public boolean getHideAvailable()   { return this.state.hideAvailable; }
    public void toggleHideAvailable()   { this.state.hideAvailable = !this.state.hideAvailable; this.rowsDirty = true; }

    /** 列ヘッダクリック: 同じ列なら昇順/降順をトグル、別列なら切替 (本家 setSortCriteria に倣う)。 */
    public void setSortColumn(SortColumn c)
    {
        if (this.state.sortColumn == c)
        {
            this.state.sortReverse = !this.state.sortReverse;
        }
        else
        {
            this.state.sortColumn = c;
            this.state.sortReverse = c == SortColumn.NAME;
        }

        this.rowsDirty = true;
    }

    public void ignore(Item it)    { this.state.ignored.add(it); this.rowsDirty = true; }
    public void clearIgnored()     { this.state.ignored.clear(); this.rowsDirty = true; }
    public boolean hasIgnored()    { return !this.state.ignored.isEmpty(); }

    /** frontier 素材 child を畳み戻せる「展開中の親」候補 (= child を直接の子に持つ、展開中の親種別)。 */
    public List<Item> getCollapseCandidates(Item child)
    {
        List<Item> out = new ArrayList<>();
        Set<Item> parents = this.childToParents.get(child);

        if (parents != null)
        {
            for (Item it : this.state.expanded)
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
            if (this.state.expanded.contains(it))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * 現在の展開状態 + 在庫ネットで、フラットな表示行を計算する。
     *
     * グローバル集計方式: 展開対象の型を親→子の topo 順で処理し、各型の「総需要」を一度だけ在庫ネットして
     * 不足分を一度だけ分解する。これにより
     *  - 在庫引き当ては型ごとにグローバル (DFS 到達順に依存しない)、
     *  - 中間素材のバッチ切り上げが出現ごとでなく総量に対して1回だけ (過大計上を回避)。
     */
    public List<MatRow> getDisplayRows()
    {
        if (!this.rowsDirty && this.cachedRows != null)
        {
            return this.cachedRows;
        }

        Object2IntOpenHashMap<Item> stock = new Object2IntOpenHashMap<>(this.available);
        // 展開対象 (expanded ∩ expandable) の型 -> まだ分解していない総需要、と代表ノード (分解時の parent/recipe 用)。
        Map<Item, Long> pendingGross = new HashMap<>();
        Map<Item, CraftNode> repNode = new HashMap<>();
        // frontier (集める単位) の型 -> 総需要、と choosable 代表ノード。
        LinkedHashMap<Item, Long> frontierGross = new LinkedHashMap<>();
        Map<Item, CraftNode> choosable = new HashMap<>();

        // seed: schematic 直材。
        for (CraftNode r : this.roots)
        {
            this.addGross(r, r.count, pendingGross, repNode, frontierGross, choosable);
        }

        // 展開対象を親→子の順で処理。各型の総需要を1回だけ在庫ネット + 分解する。
        // 型 DAG に cycle がありうる (stone↔cobblestone: MC 26.2 の stonecutter で
        // stone→cobblestone があり、furnace の smelt は cobblestone→stone なので相互に親子)。
        // topo は cycle 検出して各 type を 1 度だけ処理するが、cycle 内 type に対し後段で
        // 別 path から addGross された分が残り、それが消滅するのを防ぐため、処理済みの type は
        // pendingGross を 0 で上書き → 残量を持つ type は最後に frontier へ逃がす。
        for (Item x : this.topoExpandedTypes())
        {
            Long g = pendingGross.get(x);

            if (g == null || g <= 0)
            {
                continue;
            }

            long gross = g;
            pendingGross.put(x, 0L); // 処理済みマーク (この loop 内で cycle 経由 + される分は後段で拾う)
            int have = stock.getInt(x);
            long toCraft = Math.max(0L, gross - have);
            CraftNode rep = repNode.get(x);

            if (toCraft <= 0 || rep == null)
            {
                // 完全充足 → 展開停止。covered な frontier 行として出す (緑「0 (在庫)」)。
                frontierGross.merge(x, gross, Long::sum);

                if (rep != null && rep.isChoosable())
                {
                    choosable.putIfAbsent(x, rep);
                }
                continue;
            }

            // 不足分 toCraft を一度だけ分解 (レシピ収量の切り上げが総量に対して1回)。
            MaterialListJsonBase rebuilt = new MaterialListJsonBase(rep.item, (int) toCraft, rep.parentItem, this.craftingOnly);
            CraftNode decomposed = CraftNode.fromBase(rebuilt, rep.depth, this.state.materialOverride, this.craftingOnly);

            // 再分解で展開可能な子が無いケース (Stone の smelting レシピ ingredient が prev と一致して
            // MaterialListJsonBase の checkIfLoop が発火 → materialsRemaining 落ち、fromBase の追跡対象から外れる)。
            // このまま frontier にも pending にも積まないと行が消滅するので、leaf として frontier に残す。
            if (decomposed.children.isEmpty())
            {
                Reference.LOGGER.debug("[rawmats] re-decomposition produced no children for {} (prev={}); keeping as leaf",
                        BuiltInRegistries.ITEM.getKey(rep.item.value()),
                        rep.parentItem != null ? BuiltInRegistries.ITEM.getKey(rep.parentItem.value()) : "<root>");
                frontierGross.merge(x, gross, Long::sum);

                if (rep.isChoosable())
                {
                    choosable.putIfAbsent(x, rep);
                }
                continue;
            }

            stock.addTo(x, -(int) Math.min(have, gross)); // 在庫を消費 (子の処理で参照される)

            for (CraftNode c : decomposed.children)
            {
                this.addGross(c, c.count, pendingGross, repNode, frontierGross, choosable);
            }
        }

        // 型 DAG cycle の救済: topo 1 回処理で消費されずに残った pending 量 (cycle 内 type で
        // 後段に addGross された分) を frontier に逃がす。これがないと cycle 内 type は表示行から消滅する。
        for (Map.Entry<Item, Long> en : pendingGross.entrySet())
        {
            long gross = en.getValue();

            if (gross <= 0)
            {
                continue;
            }

            Item x = en.getKey();
            Reference.LOGGER.debug("[rawmats] unprocessed pending residual {} = {} after topo (cycle); treating as leaf",
                    BuiltInRegistries.ITEM.getKey(x), gross);
            frontierGross.merge(x, gross, Long::sum);
            CraftNode rep = repNode.get(x);

            if (rep != null && rep.isChoosable())
            {
                choosable.putIfAbsent(x, rep);
            }
        }

        // frontier を在庫ネットして行に。
        List<MatRow> rows = new ArrayList<>();
        for (Map.Entry<Item, Long> en : frontierGross.entrySet())
        {
            Item it = en.getKey();

            if (this.state.ignored.contains(it))
            {
                continue;
            }

            int have = this.available.getInt(it);
            int gross = (int) Math.min(Integer.MAX_VALUE, en.getValue());
            int net = (int) Math.max(0L, en.getValue() - have);

            if (this.state.hideAvailable && net <= 0)
            {
                continue;
            }

            Holder<Item> holder = BuiltInRegistries.ITEM.wrapAsHolder(it);
            boolean expandable = this.expandableTypes.contains(it) && !this.state.expanded.contains(it);
            boolean foldable = this.hasCollapseCandidate(it);
            CraftNode cn = choosable.get(it);
            rows.add(new MatRow(holder, gross, net, have, expandable, foldable,
                    cn != null, cn != null ? cn.choiceKey : null, cn != null ? cn.choices : null));
        }

        this.sortRows(rows);
        this.cachedRows = rows;
        this.rowsDirty = false;
        return rows;
    }

    /** 需要 qty を、展開対象なら pending に、そうでなければ frontier に積む。 */
    private void addGross(CraftNode node, long qty,
            Map<Item, Long> pendingGross, Map<Item, CraftNode> repNode,
            Map<Item, Long> frontierGross, Map<Item, CraftNode> choosable)
    {
        Item it = node.item.value();

        if (this.state.expanded.contains(it) && this.expandableTypes.contains(it))
        {
            pendingGross.merge(it, qty, Long::sum);
            repNode.putIfAbsent(it, node);
        }
        else
        {
            frontierGross.merge(it, qty, Long::sum);

            if (node.isChoosable())
            {
                choosable.putIfAbsent(it, node);
            }
        }
    }

    /** 展開対象 (expanded ∩ expandable) の型を、型 DAG ({@link #typeChildren}) の親→子 topo 順で返す。 */
    private List<Item> topoExpandedTypes()
    {
        Set<Item> nodes = new LinkedHashSet<>();

        for (Item it : this.state.expanded)
        {
            if (this.expandableTypes.contains(it))
            {
                nodes.add(it);
            }
        }

        List<Item> order = new ArrayList<>();
        Set<Item> visited = new HashSet<>();
        Set<Item> inStack = new HashSet<>();

        for (Item it : nodes)
        {
            this.topoVisit(it, nodes, visited, inStack, order);
        }

        java.util.Collections.reverse(order); // post-order (子先) を反転して親先に
        return order;
    }

    private void topoVisit(Item x, Set<Item> nodes, Set<Item> visited, Set<Item> inStack, List<Item> order)
    {
        if (visited.contains(x) || inStack.contains(x))
        {
            return; // 訪問済み / 循環 (型 DAG 想定だが念のためガード)
        }

        inStack.add(x);
        Set<Item> kids = this.typeChildren.get(x);

        if (kids != null)
        {
            for (Item y : kids)
            {
                if (nodes.contains(y))
                {
                    this.topoVisit(y, nodes, visited, inStack, order);
                }
            }
        }

        inStack.remove(x);
        visited.add(x);
        order.add(x);
    }

    private void sortRows(List<MatRow> rows)
    {
        java.util.Comparator<MatRow> cmp = switch (this.state.sortColumn)
        {
            case NAME      -> java.util.Comparator.comparing(r -> new ItemStack(r.item).getHoverName().getString(),
                                    String.CASE_INSENSITIVE_ORDER);
            case TOTAL     -> java.util.Comparator.comparingInt(r -> r.total);
            case MISSING   -> java.util.Comparator.comparingInt(r -> r.need);
            case AVAILABLE -> java.util.Comparator.comparingInt(r -> r.have);
        };

        if (this.state.sortReverse)
        {
            cmp = cmp.reversed();
        }

        rows.sort(cmp);
    }
}
