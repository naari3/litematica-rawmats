package io.github.naari3.rawmats.materials;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;

//? if >=26.1 {
import fi.dy.masa.malilib.util.data.ItemType;
//?} else {
/*import fi.dy.masa.malilib.util.ItemType;
*///?}
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.materials.MaterialListUtils;
import fi.dy.masa.litematica.materials.json.MaterialListJsonBase;

import io.github.naari3.rawmats.Reference;

/**
 * 「編集可能なフラット買い物リスト」のモデル。
 *
 * - root = schematic 直材。各々を {@link MaterialListJsonBase} で再帰展開した baked ツリー ({@link CraftNode}) を保持。
 * - 集計の単位は {@link Mat} (具体アイテム / タグ材料の「任意」)。タグ材料は既定で「任意」として数え、
 *   ユーザーが具体素材を選ぶとその素材で数える ({@link #applyChoices})。
 * - 表示状態 = 「展開した単位」の集合ほか ({@link CraftTreeState})。source から分離して保持。
 * - 表示 ({@link #getDisplayRows}) = 展開対象の単位を親→子 topo 順で処理し、各単位の総需要を1回だけ
 *   在庫ネット + 分解 → frontier (集める単位) を同一単位で合算したフラットリスト ({@link MatRow})。
 * - 在庫ネットは単位ごとにグローバル (DFS 到達順に非依存)。中間素材のバッチ切り上げは総量に対して1回
 *   (出現ごとの過大計上を回避)。完全充足の展開対象は展開停止 (covered)。
 *   在庫の引き当ては具体アイテムを先、「任意」は後 (狭い集合から)。
 * - 畳み (collapse): frontier 素材 I は「I を直接の子に持つ展開中の親 P」由来。collapse(P) で P を畳み戻す。
 *   I を畳む候補 = {@link #getCollapseCandidates}。
 * - ignore: その素材と、そこから派生する原料需要を集計から除外する ({@link #addGross} で遮断)。
 *   Clear ignored で全解除。
 */
public class CraftTree
{
    /** 「任意」の分解で、メンバーの分解形を比べるときの個数 (1〜10 の公倍数。収量の違いを取りこぼさない)。 */
    private static final int SHAPE_PROBE = 5040;

    private final List<CraftNode> roots = new ArrayList<>();
    private final Set<Mat> expandableTypes = new HashSet<>();
    /** 子の単位 -> それを直接の子に持つ親の単位の集合 (畳み候補の逆引き)。 */
    private final Map<Mat, Set<Mat>> childToParents = new HashMap<>();
    /** 親の単位 -> その直接の子の単位の集合 (展開対象を topo 順に処理するための型 DAG)。 */
    private final Map<Mat, Set<Mat>> typeChildren = new HashMap<>();
    /** ツリーに現れた単位 (ID -> Mat)。除外一覧の表示など、ID から Mat を引くのに使う。 */
    private final Map<String, Mat> knownMats = new HashMap<>();
    private final Object2IntOpenHashMap<Item> available = new Object2IntOpenHashMap<>();
    /** セッション内ビュー状態 (展開集合 / タグ置換 / 倍率)。source キーごとに {@link CraftTreeStore} で復元される。 */
    private final CraftTreeState state;
    /** 直近の source。タグ材料の置換時にツリーを作り直すため保持。 */
    private MaterialListBase source;
    /** 「任意」の分解形のキャッシュ (集合 ID + 代表 -> 形)。レシピは起動中に変わらない前提でツリーの寿命だけ持つ。 */
    private final Map<String, Optional<AnyShape>> anyShapes = new HashMap<>();
    /** メンバー集合 -> 一致する item タグ (「任意」の和集合に名前を付ける)。 */
    private final Map<Set<Item>, Optional<TagKey<Item>>> tagLookup = new HashMap<>();

    /** crafting と stonecutter が両立する場合に crafting を優先。 */
    private boolean craftingOnly = true;

    /** {@link #getDisplayRows} のキャッシュ。状態変化 (展開/在庫/ソート/hide/ignore/倍率/タグ) 時のみ再計算する。 */
    private List<MatRow> cachedRows;
    private boolean rowsDirty = true;

    /** 表示の並べ替え列 (本家 GuiMaterialList と同じ4列)。状態は {@link CraftTreeState} 側でセッション保持。 */
    public enum SortColumn { NAME, TOTAL, MISSING, AVAILABLE }

    /** rebuild の入力: 直材 1 種の必要数 (倍率適用前)。source 非依存の投入 (テスト等) にも使う。 */
    public record Demand(Holder<Item> item, int count) {}

    /**
     * (item, count, parent) を再帰展開済みの {@link CraftNode} ツリーに分解する関数。
     * production は {@link MaterialListJsonBase} 実装 (client の recipe book に依存し、
     * 実クライアント無しでは動かない)。テストは fake レシピテーブル実装を注入する。
     * タグ材料の子には {@link CraftNode#choice} を付けて返す (「任意」化と選択の適用は {@link #applyChoices})。
     */
    @FunctionalInterface
    public interface Decomposer
    {
        CraftNode decompose(Holder<Item> item, int count, @Nullable Holder<Item> parent, int depth);
    }

    /**
     * 「任意」{@code AnyOf} の分解形。メンバーを同じ個数で分解し、代表と形 (材料数・各材料の個数) が
     * そろうメンバー ({@link #group}) をまとめたもの。
     *
     * @param slotAny   スロットごとの「任意」(メンバーごとに材料が違うスロット)。材料が共通のスロットは null。
     * @param slotItems メンバー -> スロットごとの材料アイテム (展開先から親の選択を変えるメニューに使う)。
     * @param excluded  形がそろわない、またはレシピが無いメンバー。
     */
    public record AnyShape(List<Holder<Item>> group, List<Mat.AnyOf> slotAny,
            Map<Item, List<Holder<Item>>> slotItems, List<Holder<Item>> excluded) {}

    private final Decomposer decomposer;
    /** 直近の rebuild 入力 (倍率適用前)。source 無しでも倍率変更等で再構築できるよう保持。 */
    private List<Demand> demands;

    public CraftTree(MaterialListBase source, CraftTreeState state)
    {
        this.state = state;
        this.decomposer = this::decomposeWithRecipeBook;
        this.rebuild(source);
    }

    /** テスト用: litematica の source に依存せず、需要リストと分解関数から構築する。 */
    public CraftTree(List<Demand> demands, CraftTreeState state, Decomposer decomposer)
    {
        this.state = state;
        this.decomposer = decomposer;
        this.rebuildFromDemands(demands);
    }

    private CraftNode decomposeWithRecipeBook(Holder<Item> item, int count, @Nullable Holder<Item> parent, int depth)
    {
        MaterialListJsonBase base = new MaterialListJsonBase(item, count, parent, this.craftingOnly);
        return CraftNode.fromBase(base, depth);
    }

    /** 単位 m を count 個ぶん分解する (タグ材料の「任意」化と選択の適用込み)。 */
    private CraftNode decomposeMat(Mat m, int count, @Nullable Holder<Item> parent, int depth)
    {
        if (m instanceof Mat.AnyOf any)
        {
            return this.decomposeAny(any, count, parent, depth);
        }

        CraftNode node = this.decomposer.decompose(m.rep(), count, parent, depth);
        this.applyChoices(node);
        return node;
    }

    /**
     * node の子のうちタグ材料 ({@link CraftNode#choice}) を、ユーザー選択があればその具体素材で、
     * 無ければ「任意」で作り直す。具体材料の子はそのまま再帰する。
     */
    void applyChoices(CraftNode node)
    {
        for (int i = 0; i < node.children.size(); i++)
        {
            node.children.set(i, this.resolveChild(node, node.children.get(i)));
        }
    }

    private CraftNode resolveChild(CraftNode parent, CraftNode child)
    {
        Mat.AnyOf choice = child.choice;

        if (choice == null || choice.size() <= 1)
        {
            this.applyChoices(child);
            return child;
        }

        Holder<Item> chosen = this.state.materialOverride.get(choice.id());
        CraftNode out;

        if (chosen != null && choice.contains(chosen.value()))
        {
            out = chosen.value() == child.item.value()
                    ? child
                    : this.decomposer.decompose(chosen, child.count, parent.item, child.depth);
            this.applyChoices(out);
            this.markUpstream(out, choice);
        }
        else
        {
            out = this.decomposeAny(choice.withRep(child.item), child.count, parent.item, child.depth);
        }

        out.choice = choice;
        out.parentItem = parent.item;
        return out;
    }

    /**
     * 具体指定されたタグスロットの分解 (例: #planks で選んだ crimson_planks) の子のうち、
     * メンバーごとに材料が違うスロット (crimson_stem) に親のタグを記録する。展開先の行から親の選択を変えるため。
     */
    private void markUpstream(CraftNode chosenNode, Mat.AnyOf choice)
    {
        AnyShape shape = this.anyShape(choice.withRep(chosenNode.item));

        if (shape == null || shape.slotItems().get(chosenNode.item.value()) == null)
        {
            return;
        }

        for (int j = 0; j < chosenNode.children.size() && j < shape.slotAny().size(); j++)
        {
            if (shape.slotAny().get(j) != null)
            {
                CraftNode gc = chosenNode.children.get(j);
                gc.upstream = choice;
                gc.upstreamSlot = j;
            }
        }
    }

    /**
     * 「任意」a を count 個ぶん分解する。代表の分解を土台に、メンバーごとに材料が違うスロットを
     * 和集合の「任意」に差し替える ({@link AnyShape})。形が取れない (レシピ無し・循環) なら
     * 代表の分解をそのまま使う。結果のノードは a として数える ({@link CraftNode#any})。
     */
    private CraftNode decomposeAny(Mat.AnyOf a, int count, @Nullable Holder<Item> parent, int depth)
    {
        CraftNode node = this.decomposer.decompose(a.rep(), count, parent, depth);
        AnyShape shape = this.anyShape(a);

        if (shape != null && shape.slotAny().size() == node.children.size())
        {
            for (int i = 0; i < node.children.size(); i++)
            {
                Mat.AnyOf slot = shape.slotAny().get(i);
                CraftNode child = node.children.get(i);

                if (slot != null)
                {
                    child.choice = slot.withRep(child.item);
                }

                node.children.set(i, this.resolveChild(node, child));
            }
        }
        else
        {
            this.applyChoices(node);
        }

        node.any = a;
        return node;
    }

    /** a の分解形 (キャッシュ付き)。代表にレシピが無い、または和集合が a 自身と重なる (循環) なら null。 */
    @Nullable
    public AnyShape anyShape(Mat.AnyOf a)
    {
        String key = a.id() + "|" + BuiltInRegistries.ITEM.getKey(a.rep().value());
        return this.anyShapes.computeIfAbsent(key, k -> Optional.ofNullable(this.computeAnyShape(a))).orElse(null);
    }

    @Nullable
    private AnyShape computeAnyShape(Mat.AnyOf a)
    {
        CraftNode repNode = this.decomposer.decompose(a.rep(), SHAPE_PROBE, null, 0);

        if (repNode.children.isEmpty())
        {
            return null;
        }

        int slots = repNode.children.size();
        List<Holder<Item>> group = new ArrayList<>();
        List<Holder<Item>> excluded = new ArrayList<>();
        Map<Item, List<Holder<Item>>> slotItems = new LinkedHashMap<>();
        List<Map<Item, Holder<Item>>> slotSets = new ArrayList<>();
        List<Set<Set<Item>>> slotVariants = new ArrayList<>();

        for (int i = 0; i < slots; i++)
        {
            slotSets.add(new LinkedHashMap<>());
            slotVariants.add(new HashSet<>());
        }

        for (Holder<Item> m : a.members())
        {
            CraftNode mn = m.value() == a.rep().value() ? repNode : this.decomposer.decompose(m, SHAPE_PROBE, null, 0);

            if (!sameShape(repNode, mn))
            {
                excluded.add(m);
                continue;
            }

            group.add(m);
            List<Holder<Item>> items = new ArrayList<>();

            for (int i = 0; i < slots; i++)
            {
                CraftNode c = mn.children.get(i);
                List<Holder<Item>> accepts = c.choice != null ? c.choice.members() : List.of(c.item);
                Set<Item> variant = new HashSet<>();

                for (Holder<Item> h : accepts)
                {
                    slotSets.get(i).putIfAbsent(h.value(), h);
                    variant.add(h.value());
                }

                slotVariants.get(i).add(variant);
                items.add(c.item);
            }

            slotItems.put(m.value(), items);
        }

        List<Mat.AnyOf> slotAny = new ArrayList<>();

        for (int i = 0; i < slots; i++)
        {
            if (slotVariants.get(i).size() <= 1)
            {
                slotAny.add(null); // 全メンバーで材料が共通
                continue;
            }

            Map<Item, Holder<Item>> union = slotSets.get(i);

            for (Item it : union.keySet())
            {
                if (a.contains(it))
                {
                    return null; // 循環 (任意の羊毛 -> 任意の羊毛 + 染料 等)
                }
            }

            List<Holder<Item>> members = new ArrayList<>(union.values());
            slotAny.add(new Mat.AnyOf(this.findTag(union.keySet()), members, repNode.children.get(i).item));
        }

        return new AnyShape(group, slotAny, slotItems, excluded);
    }

    /** 材料数と各スロットの個数が一致するか (スロットの並びは分解結果の順)。 */
    private static boolean sameShape(CraftNode a, CraftNode b)
    {
        if (a.children.size() != b.children.size())
        {
            return false;
        }

        for (int i = 0; i < a.children.size(); i++)
        {
            if (a.children.get(i).count != b.children.get(i).count)
            {
                return false;
            }
        }

        return true;
    }

    /** members とちょうど同じメンバーを持つ item タグ。無ければ null (直接の集合として扱う)。 */
    @Nullable
    private TagKey<Item> findTag(Set<Item> members)
    {
        return this.tagLookup.computeIfAbsent(Set.copyOf(members), set ->
        {
            try
            {
                return BuiltInRegistries.ITEM.getTags()
                        .filter(named -> named.size() == set.size() && containsAll(named, set))
                        .map(HolderSet.Named::key)
                        .min(Comparator.comparing(k -> k.location().toString()));
            }
            catch (Exception e)
            {
                return Optional.empty(); // タグ未同期 (テスト環境等)
            }
        }).orElse(null);
    }

    private static boolean containsAll(HolderSet<Item> set, Set<Item> items)
    {
        for (Holder<Item> h : set)
        {
            if (!items.contains(h.value()))
            {
                return false;
            }
        }

        return true;
    }

    /**
     * 展開先の行 (例: crimson_stem) から親のタグ (#planks) の選択を変えるときの候補。
     * 親タグのメンバーごとに、そのスロットの材料アイテム (crimson_planks -> crimson_stem 等) を返す。
     */
    public List<UpstreamOption> upstreamOptions(CraftNode node)
    {
        List<UpstreamOption> out = new ArrayList<>();

        if (node.upstream == null || node.parentItem == null)
        {
            return out;
        }

        AnyShape shape = this.anyShape(node.upstream.withRep(node.parentItem));

        if (shape == null)
        {
            return out;
        }

        for (Holder<Item> m : shape.group())
        {
            List<Holder<Item>> items = shape.slotItems().get(m.value());

            if (items != null && node.upstreamSlot >= 0 && node.upstreamSlot < items.size())
            {
                out.add(new UpstreamOption(m, items.get(node.upstreamSlot)));
            }
        }

        return out;
    }

    /** 親タグのメンバー {@code member} を選ぶと、展開先のスロットが {@code slotItem} になる。 */
    public record UpstreamOption(Holder<Item> member, Holder<Item> slotItem) {}

    public void rebuild(MaterialListBase source)
    {
        this.source = source;
        List<Demand> list = new ArrayList<>();

        for (MaterialListEntry e : source.getMaterialsAll())
        {
            //? if >=26.1 {
            list.add(new Demand(e.getStack().typeHolder(), e.getStack().getCount() * e.getCountTotal()));
            //?} else {
            /*list.add(new Demand(e.getStack().getItemHolder(), e.getStack().getCount() * e.getCountTotal()));
            *///?}
        }

        this.rebuildFromDemands(list);
    }

    /** 需要リスト (倍率適用前) からツリーを構築する。倍率はここで適用する。 */
    public void rebuildFromDemands(List<Demand> demands)
    {
        this.demands = List.copyOf(demands);
        this.roots.clear();
        this.expandableTypes.clear();
        this.childToParents.clear();
        this.typeChildren.clear();
        this.knownMats.clear();

        int mult = Math.max(1, this.state.multiplier);

        for (Demand d : this.demands)
        {
            CraftNode node = this.decomposeMat(Mat.of(d.item()), d.count() * mult, null, 0);
            this.roots.add(node);
            this.indexNode(node);
        }

        this.refreshAvailable();
        this.rowsDirty = true;
    }

    public int getMultiplier() { return this.state.multiplier; }

    /** 書き出し用: source の表示タイトル。 */
    public String getSourceTitle() { return this.source != null ? this.source.getTitle() : ""; }

    /** 書き出し用: ベイク済み root ノード群 (schematic 直材)。 */
    public List<CraftNode> getRoots() { return this.roots; }

    /** 書き出し用: タグ材料の置換選択 (集合 ID -> 具体素材)。ユーザーが具体素材を選んだものだけ含む。 */
    public Map<String, Holder<Item>> getMaterialOverrides() { return this.state.materialOverride; }

    /** タグ key に選ばれている具体素材。「任意」なら null。 */
    @Nullable
    public Holder<Item> getMaterialOverride(@Nullable TagKey<Item> key)
    {
        return key != null ? this.state.materialOverride.get("#" + key.location()) : null;
    }

    /** 倍率を設定し (1 以上にクランプ)、変化があればツリーを作り直す (展開状態・タグ選択は維持)。 */
    public void setMultiplier(int multiplier)
    {
        int clamped = Math.max(1, multiplier);

        if (clamped != this.state.multiplier)
        {
            this.state.multiplier = clamped;
            this.rebuildFromCurrentInput();
        }
    }

    /** タグ材料 key に具体素材 item を割り当てる。item が null なら「任意」に戻す。ツリーを再構築する (展開状態は維持)。 */
    public void chooseMaterial(Mat.AnyOf key, @Nullable Holder<Item> item)
    {
        if (item == null)
        {
            this.state.materialOverride.remove(key.id());
        }
        else
        {
            this.state.materialOverride.put(key.id(), item);
        }

        this.rebuildFromCurrentInput();
    }

    /** source があれば source から (直材を再読込)、無ければ保持している需要リストから再構築する。 */
    private void rebuildFromCurrentInput()
    {
        if (this.source != null)
        {
            this.rebuild(this.source);
        }
        else if (this.demands != null)
        {
            this.rebuildFromDemands(this.demands);
        }
    }

    private void indexNode(CraftNode n)
    {
        Mat parent = n.mat();
        this.knownMats.putIfAbsent(parent.id(), parent);

        if (n.isExpandable())
        {
            this.expandableTypes.add(parent);
            Set<Mat> kids = this.typeChildren.computeIfAbsent(parent, k -> new LinkedHashSet<>());

            for (CraftNode c : n.children)
            {
                Mat child = c.mat();
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
        this.rowsDirty = true;
        Minecraft mc = Minecraft.getInstance();
        Player player = mc != null ? mc.player : null;

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

    /** 単位 m の在庫数 (「任意」はメンバーの合計)。他の行との引き当ては考慮しない素の数。 */
    public int availableFor(Mat m)
    {
        return (int) Math.min(Integer.MAX_VALUE, haveOf(m, this.available));
    }

    /** テスト用: プレイヤーインベントリの代わりに在庫を直接設定する ({@link #refreshAvailable} で消える)。 */
    void setAvailableForTest(Item item, int count) { this.available.put(item, count); this.rowsDirty = true; }

    private static long haveOf(Mat m, Object2IntMap<Item> stock)
    {
        if (m instanceof Mat.AnyOf any)
        {
            long sum = 0;

            for (Item it : any.memberSet())
            {
                sum += Math.max(0, stock.getInt(it));
            }

            return sum;
        }

        return Math.max(0, stock.getInt(m.rep().value()));
    }

    /** 単位 m の在庫を amount だけ消費する (「任意」は在庫の多いメンバーから)。 */
    private static void consume(Mat m, long amount, Object2IntOpenHashMap<Item> stock)
    {
        if (amount <= 0)
        {
            return;
        }

        if (!(m instanceof Mat.AnyOf any))
        {
            stock.addTo(m.rep().value(), -(int) Math.min(Integer.MAX_VALUE, amount));
            return;
        }

        List<Item> members = new ArrayList<>(any.memberSet());
        members.sort(Comparator.comparingInt(stock::getInt).reversed());
        long left = amount;

        for (Item it : members)
        {
            int have = stock.getInt(it);

            if (left <= 0 || have <= 0)
            {
                break;
            }

            int take = (int) Math.min(have, left);
            stock.addTo(it, -take);
            left -= take;
        }
    }

    /**
     * デバッグ用: ベイク済みツリー構造 + フラット表示行をログに出す (Expand all 押下時に呼ばれる)。
     * 各ノードの単位 / count / 子数 / 展開可否 / タグ材料の情報と、
     * 在庫差引後の表示行 (need/have/expandable/foldable/choosable) を確認できる。
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
                    r.mat.id(), r.need, r.have, r.expandable, r.foldable, r.choosable);
        }
    }

    /**
     * デバッグ用: 状態を丸ごとテキストにする (GUI の Dump state ボタンでファイルに書き出す)。
     * 保存状態 (展開 / 除外 / 選択 / 倍率)、型 DAG、ベイク済みツリー、分解パス (frontier / 在庫の引き当て / crafted)、
     * 表示行、「任意」の分解形を含む。
     */
    public String dumpState()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("== state\n").append(this.state.toJson()).append('\n');
        sb.append("source: ").append(this.getSourceTitle()).append('\n');

        sb.append("\n== available (inventory)\n");
        this.available.object2IntEntrySet().stream()
                .filter(en -> en.getIntValue() > 0)
                .map(en -> "  " + BuiltInRegistries.ITEM.getKey(en.getKey()) + " = " + en.getIntValue())
                .sorted()
                .forEach(l -> sb.append(l).append('\n'));

        sb.append("\n== expandable types (").append(this.expandableTypes.size()).append(")\n");
        this.expandableTypes.stream().map(Mat::id).sorted().forEach(id ->
        {
            Mat m = this.knownMats.get(id);
            sb.append("  ").append(id)
              .append(this.state.expanded.contains(id) ? " [expanded]" : "")
              .append(" -> ").append(m != null && this.typeChildren.containsKey(m)
                      ? this.typeChildren.get(m).stream().map(Mat::id).toList() : List.of())
              .append('\n');
        });

        sb.append("\n== tree\n");
        for (CraftNode r : this.roots)
        {
            this.dumpNodeTo(r, sb);
        }

        NetPass pass = this.computeNetPass();
        sb.append("\n== topo (expanded, processing order)\n");
        for (Mat m : this.topoExpandedTypes())
        {
            sb.append("  ").append(m.id()).append('\n');
        }

        sb.append("\n== crafted\n");
        for (NetPass.Crafted c : pass.crafted.values())
        {
            sb.append("  ").append(c.mat().id()).append(" gross=").append(c.gross()).append(" toCraft=").append(c.toCraft())
              .append(" children=");
            c.childCounts().forEach((k, v) -> sb.append(k.id()).append('x').append(v).append(' '));
            sb.append('\n');
        }

        sb.append("\n== frontier\n");
        for (Map.Entry<Mat, Long> en : pass.frontierGross.entrySet())
        {
            CraftNode cn = pass.choosable.get(en.getKey());
            sb.append("  ").append(en.getKey().id()).append(" gross=").append(en.getValue())
              .append(" have=").append(pass.frontierHave.get(en.getKey()))
              .append(" expandable=").append(this.isExpandable(en.getKey()))
              .append(" expanded=").append(this.isExpandedType(en.getKey()))
              .append(" foldable=").append(this.hasCollapseCandidate(en.getKey()))
              .append(" collapseTo=").append(this.getCollapseCandidates(en.getKey()).stream().map(Mat::id).toList())
              .append(" choice=").append(cn != null && cn.choice != null ? cn.choice.id() : null)
              .append(" upstream=").append(cn != null && cn.upstream != null ? cn.upstream.id() + "@" + cn.upstreamSlot : null)
              .append('\n');
        }

        sb.append("\n== rows (as displayed)\n");
        for (MatRow r : this.getDisplayRows())
        {
            sb.append("  ").append(r.mat.id()).append(" total=").append(r.total).append(" need=").append(r.need)
              .append(" have=").append(r.have).append(" expandable=").append(r.expandable)
              .append(" foldable=").append(r.foldable).append(" choosable=").append(r.choosable).append('\n');
        }

        sb.append("\n== any shapes\n");
        this.anyShapes.forEach((k, v) ->
        {
            sb.append("  ").append(k).append(" -> ");

            if (v.isEmpty())
            {
                sb.append("none\n");
                return;
            }

            AnyShape s = v.get();
            sb.append("group=").append(s.group().stream().map(h -> BuiltInRegistries.ITEM.getKey(h.value()).toString()).toList())
              .append(" slotAny=").append(s.slotAny().stream().map(a -> a != null ? a.id() : "-").toList())
              .append(" excluded=").append(s.excluded().stream().map(h -> BuiltInRegistries.ITEM.getKey(h.value()).toString()).toList())
              .append('\n');
        });

        return sb.toString();
    }

    private void dumpNodeTo(CraftNode n, StringBuilder sb)
    {
        sb.append("  ".repeat(n.depth + 1)).append(n.mat().id()).append(" x").append(n.count)
          .append(n.choice != null ? " choice=" + n.choice.id() : "")
          .append(n.upstream != null ? " upstream=" + n.upstream.id() + "@" + n.upstreamSlot : "")
          .append('\n');

        for (CraftNode c : n.children)
        {
            this.dumpNodeTo(c, sb);
        }
    }

    private void dumpNode(CraftNode n)
    {
        String indent = "  ".repeat(n.depth);
        Reference.LOGGER.info("[rawmats] {}{} x{} children={} expandable={} choosable={} choice={} upstream={}",
                indent, n.mat().id(), n.count, n.children.size(), n.isExpandable(), n.isChoosable(),
                n.choice != null ? n.choice.id() : null, n.upstream != null ? n.upstream.id() : null);

        for (CraftNode c : n.children)
        {
            this.dumpNode(c);
        }
    }

    public boolean isExpandable(Mat m) { return this.expandableTypes.contains(m) && !this.state.expanded.contains(m.id()); }

    /** 単位 m が現在展開中か (分解して表示する対象か)。 */
    public boolean isExpandedType(Mat m) { return this.expandableTypes.contains(m) && this.state.expanded.contains(m.id()); }

    public void expand(Mat m)
    {
        boolean ok = this.expandableTypes.contains(m);
        traceState("expand", m, ok);

        if (ok)
        {
            this.state.expanded.add(m.id());
            this.rowsDirty = true;
        }
    }

    public void collapse(Mat m)
    {
        traceState("collapse", m, this.state.expanded.contains(m.id()));
        this.state.expanded.remove(m.id());
        this.rowsDirty = true;
    }

    /** デバッグ時: 展開状態の変更を呼び出し元つきでログに出す (ログファイルは INFO 以上なので info で出す)。 */
    private static void traceState(String op, Mat m, boolean applies)
    {
        if (Reference.LOGGER.isDebugEnabled())
        {
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            String caller = st.length > 3 ? st[3].getClassName().replaceAll(".*\\.", "") + "#" + st[3].getMethodName() : "?";
            Reference.LOGGER.info("[rawmats] {} {} applies={} caller={}", op, m.id(), applies, caller);
        }
    }

    public void expandAll()
    {
        for (Mat m : this.expandableTypes)
        {
            this.state.expanded.add(m.id());
        }

        this.rowsDirty = true;
    }

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

    public void ignore(Mat m)    { this.state.ignored.add(m.id()); this.rowsDirty = true; }
    public void unignore(Mat m)  { this.state.ignored.remove(m.id()); this.rowsDirty = true; }

    /** 除外中の単位の一覧 (表示用スナップショット)。今のツリーに無い「任意」は表示できないので省く。 */
    public List<Mat> getIgnored()
    {
        List<Mat> out = new ArrayList<>();

        for (String id : this.state.ignored)
        {
            Mat m = this.knownMats.get(id);

            if (m == null && !id.startsWith("#") && !id.startsWith("{"))
            {
                Item item = CraftTreeState.parseItem(id);
                m = item != null ? Mat.of(BuiltInRegistries.ITEM.wrapAsHolder(item)) : null;
            }

            if (m != null)
            {
                out.add(m);
            }
        }

        return out;
    }

    public void clearIgnored()     { this.state.ignored.clear(); this.rowsDirty = true; }
    public boolean hasIgnored()    { return !this.state.ignored.isEmpty(); }

    /** frontier 素材 child を畳み戻せる「展開中の親」候補 (= child を直接の子に持つ、展開中の親)。 */
    public List<Mat> getCollapseCandidates(Mat child)
    {
        List<Mat> out = new ArrayList<>();
        Set<Mat> parents = this.childToParents.get(child);

        if (parents != null)
        {
            for (Mat p : parents)
            {
                if (this.state.expanded.contains(p.id()))
                {
                    out.add(p);
                }
            }
        }
        return out;
    }

    public boolean hasCollapseCandidate(Mat child)
    {
        Set<Mat> parents = this.childToParents.get(child);
        if (parents == null)
        {
            return false;
        }
        for (Mat p : parents)
        {
            if (this.state.expanded.contains(p.id()))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * 在庫ネット分解パスの結果。フラット表示行 ({@link #getDisplayRows}) と
     * グラフ表示 (GuiCraftDag) の共通の元データ。
     */
    public static final class NetPass
    {
        /** frontier (集める/充足で展開停止) の単位 -> 総需要。 */
        public final LinkedHashMap<Mat, Long> frontierGross = new LinkedHashMap<>();
        /** frontier の単位 -> 引き当てた在庫 (具体アイテムを先、「任意」は後で、残りの在庫から)。 */
        public final Map<Mat, Long> frontierHave = new HashMap<>();
        /** タグ材料 (素材変更可能) の単位 -> 代表ノード。 */
        public final Map<Mat, CraftNode> choosable = new HashMap<>();
        /** 分解を実行した単位 -> 内訳。挿入順 = 親→子 topo の処理順 (逆順にすると素材先)。 */
        public final LinkedHashMap<Mat, Crafted> crafted = new LinkedHashMap<>();

        /** gross = 総需要、toCraft = 在庫ネット後に実際にクラフトする数、childCounts = その材料の単位別数。 */
        public record Crafted(Mat mat, long gross, long toCraft, LinkedHashMap<Mat, Long> childCounts) {}
    }

    /**
     * 現在の展開状態 + 在庫ネットの分解パス。
     *
     * グローバル集計方式: 展開対象の単位を親→子の topo 順で処理し、各単位の「総需要」を一度だけ在庫ネットして
     * 不足分を一度だけ分解する。これにより
     *  - 在庫引き当ては単位ごとにグローバル (DFS 到達順に依存しない)、
     *  - 中間素材のバッチ切り上げが出現ごとでなく総量に対して1回だけ (過大計上を回避)。
     */
    public NetPass computeNetPass()
    {
        NetPass out = new NetPass();
        Object2IntOpenHashMap<Item> stock = new Object2IntOpenHashMap<>(this.available);
        // 展開対象 (expanded ∩ expandable) の単位 -> まだ分解していない総需要、と代表ノード (分解時の parent/recipe 用)。
        Map<Mat, Long> pendingGross = new HashMap<>();
        Map<Mat, CraftNode> repNode = new HashMap<>();
        // topo で処理済みの単位。循環検出 (分解先が処理済みしか無い = 巻き戻しの分解) に使う。
        Set<Mat> processed = new HashSet<>();

        // seed: schematic 直材。
        for (CraftNode r : this.roots)
        {
            this.addGross(r, r.count, pendingGross, repNode, out.frontierGross, out.choosable);
        }

        // 展開対象を親→子の順で処理。各単位の総需要を1回だけ在庫ネット + 分解する。
        // 型 DAG に cycle がありうる (stone↔cobblestone: MC 26.2 の stonecutter で
        // stone→cobblestone があり、furnace の smelt は cobblestone→stone なので相互に親子)。
        // topo は cycle 検出して各単位を 1 度だけ処理するが、cycle 内の単位に対し後段で
        // 別 path から addGross された分が残り、それが消滅するのを防ぐため、処理済みの単位は
        // pendingGross を 0 で上書き → 残量を持つ単位は最後に frontier へ逃がす。
        for (Mat x : this.topoExpandedTypes())
        {
            Long g = pendingGross.get(x);

            if (g == null || g <= 0)
            {
                continue;
            }

            long gross = g;
            pendingGross.put(x, 0L); // 処理済みマーク (この loop 内で cycle 経由 + される分は後段で拾う)
            processed.add(x);
            long have = haveOf(x, stock);
            long toCraft = Math.max(0L, gross - have);
            CraftNode rep = repNode.get(x);

            if (toCraft <= 0 || rep == null)
            {
                // 完全充足 → 展開停止。covered な frontier 行として出す (緑「0 (在庫)」)。
                out.frontierGross.merge(x, gross, Long::sum);

                if (rep != null && rep.isChoosable())
                {
                    out.choosable.putIfAbsent(x, rep);
                }
                continue;
            }

            // 不足分 toCraft を一度だけ分解 (レシピ収量の切り上げが総量に対して1回)。
            CraftNode decomposed = this.decomposeMat(x, (int) toCraft, rep.parentItem, rep.depth);

            // タグスロットで具体指定された素材 (#planks の crimson_planks 等) なら、展開先の行から
            // 親タグの選択を変えられるよう印を付ける (ツリー構築時の resolveChild と同じ)。
            if (rep.choice != null && x instanceof Mat.Of)
            {
                this.markUpstream(decomposed, rep.choice);
            }

            // 再分解で展開可能な子が無いケース (Stone の smelting レシピ ingredient が prev と一致して
            // MaterialListJsonBase の checkIfLoop が発火 → materialsRemaining 落ち、fromBase の追跡対象から外れる)。
            // このまま frontier にも pending にも積まないと行が消滅するので、leaf として frontier に残す。
            if (decomposed.children.isEmpty())
            {
                Reference.LOGGER.debug("[rawmats] re-decomposition produced no children for {} (prev={}); keeping as leaf",
                        x.id(), rep.parentItem != null ? BuiltInRegistries.ITEM.getKey(rep.parentItem.value()) : "<root>");
                out.frontierGross.merge(x, gross, Long::sum);

                if (rep.isChoosable())
                {
                    out.choosable.putIfAbsent(x, rep);
                }
                continue;
            }

            // 分解先の全てが「処理済みの展開単位」しか無い場合 (stone↔cobblestone のような型循環を
            // 巻き戻すだけの分解)、需要が residual として戻ってくるだけで意味がない。
            // この単位を「集める葉」として確定する = 循環チェーンの起点が frontier に立つ。
            boolean cycleOnly = true;

            for (CraftNode c : decomposed.children)
            {
                if (!processed.contains(c.mat()))
                {
                    cycleOnly = false;
                    break;
                }
            }

            if (cycleOnly)
            {
                Reference.LOGGER.debug("[rawmats] decomposition of {} only feeds already-processed cycle types; keeping as leaf",
                        x.id());
                out.frontierGross.merge(x, gross, Long::sum);

                if (rep.isChoosable())
                {
                    out.choosable.putIfAbsent(x, rep);
                }
                continue;
            }

            consume(x, Math.min(have, gross), stock); // 在庫を消費 (子の処理で参照される)

            LinkedHashMap<Mat, Long> childCounts = new LinkedHashMap<>();

            for (CraftNode c : decomposed.children)
            {
                childCounts.merge(c.mat(), (long) c.count, Long::sum);
                this.addGross(c, c.count, pendingGross, repNode, out.frontierGross, out.choosable);
            }

            out.crafted.put(x, new NetPass.Crafted(x, gross, toCraft, childCounts));
        }

        // 型 DAG cycle の救済: topo 1 回処理で消費されずに残った pending 量 (cycle 内の単位で
        // 後段に addGross された分) を frontier に逃がす。これがないと cycle 内の単位は表示行から消滅する。
        for (Map.Entry<Mat, Long> en : pendingGross.entrySet())
        {
            long gross = en.getValue();

            if (gross <= 0)
            {
                continue;
            }

            Mat x = en.getKey();
            Reference.LOGGER.debug("[rawmats] unprocessed pending residual {} = {} after topo (cycle); treating as leaf",
                    x.id(), gross);
            out.frontierGross.merge(x, gross, Long::sum);
            CraftNode rep = repNode.get(x);

            if (rep != null && rep.isChoosable())
            {
                out.choosable.putIfAbsent(x, rep);
            }
        }

        this.allocateFrontierStock(out, stock);
        return out;
    }

    /**
     * frontier の各単位に、分解パスで残った在庫を引き当てる。具体アイテムの行を先に、
     * 「任意」の行は後で、狭い集合から順に (具体指定の需要が汎用の需要に在庫を取られないように)。
     * 具体アイテムの行の have は従来どおり残り在庫そのもの (需要で頭打ちにしない)。
     */
    private void allocateFrontierStock(NetPass out, Object2IntOpenHashMap<Item> stock)
    {
        Object2IntOpenHashMap<Item> left = new Object2IntOpenHashMap<>(stock);
        List<Mat.AnyOf> anys = new ArrayList<>();

        for (Map.Entry<Mat, Long> en : out.frontierGross.entrySet())
        {
            if (en.getKey() instanceof Mat.AnyOf any)
            {
                anys.add(any);
                continue;
            }

            long have = haveOf(en.getKey(), left);
            out.frontierHave.put(en.getKey(), have);
            consume(en.getKey(), Math.min(have, en.getValue()), left);
        }

        anys.sort(Comparator.comparingInt(Mat.AnyOf::size));

        for (Mat.AnyOf any : anys)
        {
            long have = haveOf(any, left);
            out.frontierHave.put(any, have);
            consume(any, Math.min(have, out.frontierGross.get(any)), left);
        }
    }

    /** 現在の展開状態 + 在庫ネットで、フラットな表示行を計算する ({@link #computeNetPass} の frontier を行に整形)。 */
    public List<MatRow> getDisplayRows()
    {
        if (!this.rowsDirty && this.cachedRows != null)
        {
            return this.cachedRows;
        }

        NetPass pass = this.computeNetPass();

        // frontier を在庫ネットして行に (除外単位は addGross で需要ごと落ちているのでここには来ない)。
        List<MatRow> rows = new ArrayList<>();
        for (Map.Entry<Mat, Long> en : pass.frontierGross.entrySet())
        {
            Mat m = en.getKey();
            long haveL = pass.frontierHave.getOrDefault(m, 0L);
            int have = (int) Math.min(Integer.MAX_VALUE, haveL);
            int gross = (int) Math.min(Integer.MAX_VALUE, en.getValue());
            int net = (int) Math.max(0L, en.getValue() - haveL);

            if (this.state.hideAvailable && net <= 0)
            {
                continue;
            }

            boolean expandable = this.isExpandable(m);
            boolean foldable = this.hasCollapseCandidate(m);
            rows.add(new MatRow(m, gross, net, have, expandable, foldable, pass.choosable.get(m)));
        }

        this.sortRows(rows);
        this.cachedRows = rows;
        this.rowsDirty = false;
        return rows;
    }

    /** 需要 qty を、展開対象なら pending に、そうでなければ frontier に積む。ignore 済みなら需要ごと捨てる。 */
    private void addGross(CraftNode node, long qty,
            Map<Mat, Long> pendingGross, Map<Mat, CraftNode> repNode,
            Map<Mat, Long> frontierGross, Map<Mat, CraftNode> choosable)
    {
        Mat m = node.mat();

        // ignore = その素材と、そこから派生する原料需要をリストから除外する (#7)。
        // 需要の流入は root seed / 分解の子ともすべてここを通るので、この 1 箇所で
        // pending (分解対象) にも frontier (表示行) にも積まれなくなる。
        // 成果物/中間素材を除外した場合はそこから下の材料需要も丸ごと消える
        // (他の親が同じ素材を要求する分は残る)。
        if (this.state.ignored.contains(m.id()))
        {
            return;
        }

        if (this.state.expanded.contains(m.id()) && this.expandableTypes.contains(m))
        {
            pendingGross.merge(m, qty, Long::sum);
            repNode.putIfAbsent(m, node);
        }
        else
        {
            frontierGross.merge(m, qty, Long::sum);

            if (node.isChoosable())
            {
                choosable.putIfAbsent(m, node);
            }
        }
    }

    /**
     * 展開対象 (expanded ∩ expandable) の単位を、型 DAG ({@link #typeChildren}) の親→子 topo 順で返す。
     * 制約の無い単位どうしは、具体アイテムを先、「任意」を後 (狭い集合から) にする
     * (在庫を具体指定の需要から先に引き当てるため)。DFS の後順を反転するので、訪問はその逆順で行う。
     */
    private List<Mat> topoExpandedTypes()
    {
        List<Mat> nodes = new ArrayList<>();

        for (Mat m : this.expandableTypes)
        {
            if (this.state.expanded.contains(m.id()))
            {
                nodes.add(m);
            }
        }

        // 訪問順: 「任意」(広い集合から) → 具体アイテム。反転後は 具体 → 任意 (狭い集合から) になる。
        nodes.sort(Comparator.comparingInt(CraftTree::visitRank).thenComparing(Mat::id));
        Set<Mat> nodeSet = new HashSet<>(nodes);

        List<Mat> order = new ArrayList<>();
        Set<Mat> visited = new HashSet<>();
        Set<Mat> inStack = new HashSet<>();

        for (Mat m : nodes)
        {
            this.topoVisit(m, nodeSet, visited, inStack, order);
        }

        java.util.Collections.reverse(order); // post-order (子先) を反転して親先に
        return order;
    }

    /** topo の DFS 訪問順のキー (小さいほど先に訪問 = 反転後は後ろ)。 */
    private static int visitRank(Mat m)
    {
        return m instanceof Mat.AnyOf any ? -any.size() : 1;
    }

    private void topoVisit(Mat x, Set<Mat> nodes, Set<Mat> visited, Set<Mat> inStack, List<Mat> order)
    {
        if (visited.contains(x) || inStack.contains(x))
        {
            return; // 訪問済み / 循環 (型 DAG 想定だが念のためガード)
        }

        inStack.add(x);
        Set<Mat> kids = this.typeChildren.get(x);

        if (kids != null)
        {
            for (Mat y : kids)
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
        Comparator<MatRow> cmp = switch (this.state.sortColumn)
        {
            case NAME      -> Comparator.comparing(r -> r.mat.displayName(), String.CASE_INSENSITIVE_ORDER);
            case TOTAL     -> Comparator.comparingInt(r -> r.total);
            case MISSING   -> Comparator.comparingInt(r -> r.need);
            case AVAILABLE -> Comparator.comparingInt(r -> r.have);
        };

        if (this.state.sortReverse)
        {
            cmp = cmp.reversed();
        }

        rows.sort(cmp);
    }
}
