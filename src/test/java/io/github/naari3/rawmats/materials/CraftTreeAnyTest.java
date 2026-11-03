package io.github.naari3.rawmats.materials;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * タグ材料の「任意」行 (#15) のテスト。
 *
 * fake レシピ: chest は「任意の板材」8 枚。板材は種類ごとに自分の原木 (樹皮を剥いだ版も可) 1 本から 4 枚、
 * 竹の板材だけは竹のブロック 1 個から 2 枚 (形がそろわないので「任意」の分解から外れる)。
 * oak_stairs は oak_planks を具体指定する。
 */
public class CraftTreeAnyTest
{
    /** fake の材料スロット: resolved = Litematica が選ぶ具体アイテム、accepts = タグ候補 (null なら具体指定)。 */
    private record Ing(Item resolved, int count, @Nullable List<Item> accepts) {}

    private record Recipe(int outputCount, List<Ing> ingredients) {}

    private static Map<Item, Recipe> RECIPES;
    private static List<Item> PLANKS;

    @BeforeAll
    static void bootstrap()
    {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        PLANKS = List.of(Items.OAK_PLANKS, Items.SPRUCE_PLANKS, Items.BAMBOO_PLANKS);
        RECIPES = Map.of(
                Items.CHEST,         new Recipe(1, List.of(new Ing(Items.OAK_PLANKS, 8, PLANKS))),
                Items.OAK_STAIRS,    new Recipe(4, List.of(new Ing(Items.OAK_PLANKS, 6, null))),
                Items.OAK_PLANKS,    new Recipe(4, List.of(new Ing(Items.OAK_LOG, 1,
                        List.of(Items.OAK_LOG, Items.STRIPPED_OAK_LOG)))),
                Items.SPRUCE_PLANKS, new Recipe(4, List.of(new Ing(Items.SPRUCE_LOG, 1,
                        List.of(Items.SPRUCE_LOG, Items.STRIPPED_SPRUCE_LOG)))),
                Items.BAMBOO_PLANKS, new Recipe(2, List.of(new Ing(Items.BAMBOO_BLOCK, 1, null))));
    }

    private static CraftNode decompose(Holder<Item> item, int count, @Nullable Holder<Item> parent, int depth)
    {
        CraftNode node = new CraftNode(item, count, depth);
        node.parentItem = parent;
        Recipe recipe = RECIPES.get(item.value());

        if (recipe != null)
        {
            long crafts = (count + recipe.outputCount() - 1L) / recipe.outputCount();

            for (Ing ing : recipe.ingredients())
            {
                CraftNode child = decompose(holder(ing.resolved()), (int) (crafts * ing.count()), item, depth + 1);

                if (ing.accepts() != null)
                {
                    child.choice = new Mat.AnyOf(null, ing.accepts().stream().map(CraftTreeAnyTest::holder).toList(),
                            holder(ing.resolved()));
                }

                node.children.add(child);
            }
        }

        return node;
    }

    private static Holder<Item> holder(Item item)
    {
        return BuiltInRegistries.ITEM.wrapAsHolder(item);
    }

    private static CraftTree tree(CraftTree.Demand... demands)
    {
        return new CraftTree(List.of(demands), new CraftTreeState(), CraftTreeAnyTest::decompose);
    }

    private static CraftTree.Demand demand(Item item, int count)
    {
        return new CraftTree.Demand(holder(item), count);
    }

    private static Optional<MatRow> itemRow(List<MatRow> rows, Item item)
    {
        return rows.stream().filter(r -> r.mat instanceof Mat.Of && r.item.value() == item).findFirst();
    }

    private static Optional<MatRow> anyRow(List<MatRow> rows, Item... members)
    {
        Set<Item> set = Set.of(members);
        return rows.stream().filter(r -> r.mat instanceof Mat.AnyOf a && a.memberSet().equals(set)).findFirst();
    }

    private static Mat.AnyOf planksAny()
    {
        return new Mat.AnyOf(null, PLANKS.stream().map(CraftTreeAnyTest::holder).toList(), holder(Items.OAK_PLANKS));
    }

    /** タグ材料は既定で「任意」の行になり、アイコン用のメンバーを持つ。 */
    @Test
    void tagIngredientDefaultsToAny()
    {
        CraftTree tree = tree(demand(Items.CHEST, 2));
        tree.expand(Mat.of(holder(Items.CHEST)));

        List<MatRow> rows = tree.getDisplayRows();
        MatRow planks = anyRow(rows, Items.OAK_PLANKS, Items.SPRUCE_PLANKS, Items.BAMBOO_PLANKS).orElseThrow();
        assertEquals(16, planks.total);
        assertTrue(planks.choosable);
        assertTrue(itemRow(rows, Items.OAK_PLANKS).isEmpty(), "既定では oak に決め打ちしない");
    }

    /** 「任意の板材」を展開すると、形がそろう種類の原木の和集合になり、竹は除外として残る。 */
    @Test
    void expandingAnyGeneralizesIngredientsAndExcludesMismatchedShape()
    {
        CraftTree tree = tree(demand(Items.CHEST, 2));
        tree.expandAll();

        List<MatRow> rows = tree.getDisplayRows();
        MatRow logs = anyRow(rows, Items.OAK_LOG, Items.STRIPPED_OAK_LOG, Items.SPRUCE_LOG, Items.STRIPPED_SPRUCE_LOG)
                .orElseThrow(() -> new AssertionError("任意の原木の行が無い: " + rows.stream().map(r -> r.mat.id()).toList()));
        assertEquals(4, logs.total); // 板材 16 / 4

        CraftTree.AnyShape shape = tree.anyShape(planksAny());
        assertNotNull(shape);
        assertEquals(List.of(Items.BAMBOO_PLANKS), shape.excluded().stream().map(Holder::value).toList());
    }

    /** 「任意」の行は、タグ内のどのアイテムの在庫でも差し引かれる。 */
    @Test
    void anyRowCountsStockOfAnyMember()
    {
        CraftTree tree = tree(demand(Items.CHEST, 2));
        tree.expand(Mat.of(holder(Items.CHEST)));
        tree.setAvailableForTest(Items.SPRUCE_PLANKS, 10);
        tree.setAvailableForTest(Items.BAMBOO_PLANKS, 3);

        MatRow planks = anyRow(tree.getDisplayRows(), Items.OAK_PLANKS, Items.SPRUCE_PLANKS, Items.BAMBOO_PLANKS).orElseThrow();
        assertEquals(13, planks.have);
        assertEquals(3, planks.need);
    }

    /** 在庫は具体指定の行に先に引き当て、余りを「任意」の行へ回す。 */
    @Test
    void specificDemandGetsStockBeforeAny()
    {
        CraftTree tree = tree(demand(Items.CHEST, 1), demand(Items.OAK_STAIRS, 4));
        tree.expand(Mat.of(holder(Items.CHEST)));
        tree.expand(Mat.of(holder(Items.OAK_STAIRS)));
        tree.setAvailableForTest(Items.OAK_PLANKS, 10);

        List<MatRow> rows = tree.getDisplayRows();
        MatRow oak = itemRow(rows, Items.OAK_PLANKS).orElseThrow();
        assertEquals(6, oak.total);
        assertEquals(0, oak.need);

        MatRow any = anyRow(rows, Items.OAK_PLANKS, Items.SPRUCE_PLANKS, Items.BAMBOO_PLANKS).orElseThrow();
        assertEquals(8, any.total);
        assertEquals(4, any.have, "oak 10 のうち 6 は階段に使い、残り 4 が任意の板材に回る");
        assertEquals(4, any.need);
    }

    /** 具体素材を選ぶとその素材で数え、「任意」(null) で元に戻る。 */
    @Test
    void choosingSpecificAndBackToAny()
    {
        CraftTree tree = tree(demand(Items.CHEST, 1));
        tree.expand(Mat.of(holder(Items.CHEST)));

        tree.chooseMaterial(planksAny(), holder(Items.SPRUCE_PLANKS));
        List<MatRow> rows = tree.getDisplayRows();
        assertEquals(8, itemRow(rows, Items.SPRUCE_PLANKS).orElseThrow().total);
        assertTrue(anyRow(rows, Items.OAK_PLANKS, Items.SPRUCE_PLANKS, Items.BAMBOO_PLANKS).isEmpty());

        tree.chooseMaterial(planksAny(), null);
        rows = tree.getDisplayRows();
        assertTrue(itemRow(rows, Items.SPRUCE_PLANKS).isEmpty());
        assertEquals(8, anyRow(rows, Items.OAK_PLANKS, Items.SPRUCE_PLANKS, Items.BAMBOO_PLANKS).orElseThrow().total);
    }

    /** 具体素材を選んだ先の原木の行から、親タグの選択を他の種類に変えられる (issue #15 の 1 点目)。 */
    @Test
    void expandedRowOffersUpstreamSpecies()
    {
        CraftTree tree = tree(demand(Items.CHEST, 1));
        tree.chooseMaterial(planksAny(), holder(Items.SPRUCE_PLANKS));
        tree.expandAll();

        // 原木の段階の #spruce_logs も既定で「任意」(spruce の原木 / 樹皮を剥いだ原木)。
        MatRow log = anyRow(tree.getDisplayRows(), Items.SPRUCE_LOG, Items.STRIPPED_SPRUCE_LOG).orElseThrow();
        assertTrue(log.choosable);
        assertNotNull(log.choiceNode);
        assertEquals(planksAny(), log.choiceNode.upstream);

        List<CraftTree.UpstreamOption> options = tree.upstreamOptions(log.choiceNode);
        assertEquals(List.of(Items.OAK_PLANKS, Items.SPRUCE_PLANKS),
                options.stream().map(o -> o.member().value()).toList(), "竹は形がそろわないので候補に出ない");
        assertEquals(List.of(Items.OAK_LOG, Items.SPRUCE_LOG),
                options.stream().map(o -> o.slotItem().value()).toList());

        tree.chooseMaterial(log.choiceNode.upstream, holder(Items.OAK_PLANKS));
        tree.expandAll();
        List<MatRow> rows = tree.getDisplayRows();
        assertTrue(anyRow(rows, Items.SPRUCE_LOG, Items.STRIPPED_SPRUCE_LOG).isEmpty());
        assertEquals(2, anyRow(rows, Items.OAK_LOG, Items.STRIPPED_OAK_LOG).orElseThrow().total);
    }

    /** 具体指定の板材 (oak_stairs) から来た原木は、親タグが無いので種類を変える候補を持たない。 */
    @Test
    void specificRecipeKeepsSpeciesFixed()
    {
        CraftTree tree = tree(demand(Items.OAK_STAIRS, 4));
        tree.expandAll();

        MatRow log = anyRow(tree.getDisplayRows(), Items.OAK_LOG, Items.STRIPPED_OAK_LOG).orElseThrow();
        assertNotNull(log.choiceNode, "oak_logs の中では選べる");
        assertNull(log.choiceNode.upstream);
    }

    /** 状態の保存形式: 「任意」の ID はそのまま、override のタグキーは 0.4 以前の # 無し形式も読める。 */
    @Test
    void stateJsonKeepsAnyIdsAndReadsLegacyOverrideKeys()
    {
        CraftTreeState state = new CraftTreeState();
        state.expanded.add("minecraft:chest");
        state.expanded.add("#minecraft:planks");
        state.ignored.add("{minecraft:oak_log,minecraft:spruce_log}");
        state.materialOverride.put("#minecraft:planks", holder(Items.SPRUCE_PLANKS));

        JsonObject json = state.toJson();
        assertEquals("minecraft:spruce_planks",
                json.getAsJsonObject("material_overrides").get("minecraft:planks").getAsString(),
                "タグは 0.4 以前と同じ # 無しのキーで書く");

        CraftTreeState back = CraftTreeState.fromJson(json);
        assertEquals(state.expanded, back.expanded);
        assertEquals(state.ignored, back.ignored);
        assertEquals(Items.SPRUCE_PLANKS, back.materialOverride.get("#minecraft:planks").value());
        assertFalse(back.materialOverride.containsKey("minecraft:planks"));
    }
}
