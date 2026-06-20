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
import org.apache.commons.lang3.tuple.Pair;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;

import fi.dy.masa.malilib.util.data.ItemType;
import fi.dy.masa.malilib.util.game.RecipeBookUtils;
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
 * - 表示 ({@link #getDisplayRows}) = baked ツリーを辿り、展開対象は子へ in-place 置換、
 *   それ以外は frontier 葉として同一アイテムで合算 → フラットなリスト ({@link MatRow})。
 * - 在庫ネット: walk 中に在庫を引き当て、完全充足ノードは展開停止 (= 子を出さない)。
 * - 畳み (collapse): frontier 素材 I は「I を直接の子に持つ展開中の親 P」由来。collapse(P) で P を畳み戻す。
 *   I を畳む候補 = {@link #getCollapseCandidates}。
 */
public class CraftTree
{
    private final List<CraftNode> roots = new ArrayList<>();
    private final Set<Item> expandableTypes = new HashSet<>();
    /** 子アイテム種別 -> それを直接の子に持つ親アイテム種別の集合 (畳み候補の逆引き)。 */
    private final Map<Item, Set<Item>> childToParents = new HashMap<>();
    private final Object2IntOpenHashMap<Item> available = new Object2IntOpenHashMap<>();
    /** セッション内ビュー状態 (展開集合 / タグ置換 / 倍率)。source キーごとに {@link CraftTreeStore} で復元される。 */
    private final CraftTreeState state;
    /** 直近の source。タグ材料の置換時にツリーを作り直すため保持。 */
    private MaterialListBase source;

    /** crafting と stonecutter が両立する場合に crafting を優先。 */
    private boolean craftingOnly = true;

    /** 表示の並べ替え列 (本家 GuiMaterialList と同じ4列)。GUI が開いている間のみ保持 (永続しない)。 */
    public enum SortColumn { NAME, TOTAL, MISSING, AVAILABLE }
    private SortColumn sortColumn = SortColumn.TOTAL;
    private boolean sortReverse = false;
    /** missing のみ表示 (need <= 0 の充足行を隠す)。 */
    private boolean hideAvailable = false;

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

        if (n.children.isEmpty())
        {
            List<Pair<RecipeDisplayId, RecipeDisplayEntry>> lookup =
                    RecipeBookUtils.getDisplayEntryFromRecipeBook(new ItemStack(n.item),
                            List.of(RecipeBookUtils.Type.SHAPED, RecipeBookUtils.Type.SHAPELESS,
                                    RecipeBookUtils.Type.STONECUTTER, RecipeBookUtils.Type.FURNACE));
            int sz = lookup == null ? -1 : lookup.size();
            Reference.LOGGER.info("[rawmats] {}  -> LEAF {} recipeBookLookup={}", indent, id, sz);
        }

        for (CraftNode c : n.children)
        {
            this.dumpNode(c);
        }
    }

    public boolean isExpandable(Item it) { return this.expandableTypes.contains(it) && !this.state.expanded.contains(it); }

    public void expand(Item it)   { if (this.expandableTypes.contains(it)) this.state.expanded.add(it); }
    public void collapse(Item it) { this.state.expanded.remove(it); }
    public void expandAll()       { this.state.expanded.addAll(this.expandableTypes); }
    public void collapseAll()     { this.state.expanded.clear(); }

    public SortColumn getSortColumn()   { return this.sortColumn; }
    public boolean getSortInReverse()   { return this.sortReverse; }
    public boolean getHideAvailable()   { return this.hideAvailable; }
    public void toggleHideAvailable()   { this.hideAvailable = !this.hideAvailable; }

    /** 列ヘッダクリック: 同じ列なら昇順/降順をトグル、別列なら切替 (本家 setSortCriteria に倣う)。 */
    public void setSortColumn(SortColumn c)
    {
        if (this.sortColumn == c)
        {
            this.sortReverse = !this.sortReverse;
        }
        else
        {
            this.sortColumn = c;
            this.sortReverse = c == SortColumn.NAME;
        }
    }

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

    /** 現在の展開状態 + 在庫ネットで、フラットな表示行を計算する。 */
    public List<MatRow> getDisplayRows()
    {
        Object2IntOpenHashMap<Item> budget = new Object2IntOpenHashMap<>(this.available);
        LinkedHashMap<Item, int[]> needMap = new LinkedHashMap<>();
        Map<Item, CraftNode> choosable = new HashMap<>();

        for (CraftNode r : this.roots)
        {
            this.walk(r, budget, needMap, choosable);
        }

        List<MatRow> rows = new ArrayList<>();
        for (Map.Entry<Item, int[]> en : needMap.entrySet())
        {
            Item it = en.getKey();
            int net = en.getValue()[0];
            int gross = en.getValue()[1];

            if (this.hideAvailable && net <= 0)
            {
                continue;
            }

            Holder<Item> holder = BuiltInRegistries.ITEM.wrapAsHolder(it);
            boolean expandable = this.expandableTypes.contains(it) && !this.state.expanded.contains(it);
            boolean foldable = this.hasCollapseCandidate(it);
            CraftNode cn = choosable.get(it);
            rows.add(new MatRow(holder, gross, net, this.available.getInt(it), expandable, foldable,
                    cn != null, cn != null ? cn.choiceKey : null, cn != null ? cn.choices : null));
        }

        this.sortRows(rows);
        return rows;
    }

    private void sortRows(List<MatRow> rows)
    {
        java.util.Comparator<MatRow> cmp = switch (this.sortColumn)
        {
            case NAME      -> java.util.Comparator.comparing(r -> new ItemStack(r.item).getHoverName().getString(),
                                    String.CASE_INSENSITIVE_ORDER);
            case TOTAL     -> java.util.Comparator.comparingInt(r -> r.total);
            case MISSING   -> java.util.Comparator.comparingInt(r -> r.need);
            case AVAILABLE -> java.util.Comparator.comparingInt(r -> r.have);
        };

        if (this.sortReverse)
        {
            cmp = cmp.reversed();
        }

        rows.sort(cmp);
    }

    private void walk(CraftNode n, Object2IntOpenHashMap<Item> budget, Map<Item, int[]> needMap, Map<Item, CraftNode> choosable)
    {
        Item it = n.item.value();
        int alloc = Math.min(n.count, budget.getInt(it));
        budget.addTo(it, -alloc);
        int net = n.count - alloc;
        boolean covered = net == 0;

        if (!covered && this.state.expanded.contains(it) && n.isExpandable())
        {
            List<CraftNode> children = n.children;

            if (net < n.count)
            {
                // 部分在庫: 不足分 net だけを作るサブツリーを再構築し、レシピ収量 (1 log -> 4 planks 等) を
                // build() の正確な計算で反映する。在庫ゼロ (net == n.count) のときは baked をそのまま使う。
                MaterialListJsonBase rebuilt = new MaterialListJsonBase(n.item, net, n.parentItem, this.craftingOnly);
                children = CraftNode.fromBase(rebuilt, n.depth, this.state.materialOverride, this.craftingOnly).children;
            }

            for (CraftNode c : children)
            {
                this.walk(c, budget, needMap, choosable);
            }
        }
        else
        {
            int[] acc = needMap.computeIfAbsent(it, k -> new int[2]);
            acc[0] += net;        // missing (在庫差引後)
            acc[1] += n.count;    // total (gross)

            if (n.isChoosable())
            {
                choosable.putIfAbsent(it, n);
            }
        }
    }
}
