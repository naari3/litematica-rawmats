package io.github.naari3.rawmats.materials;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ignore の需要伝播 (#7) の回帰テスト。
 *
 * 分解はクライアントの recipe book に依存しないよう、バニラ相当の fake レシピテーブルを
 * {@link CraftTree.Decomposer} として注入する。アイテム実体 (registry) だけは
 * {@link Bootstrap} で本物を使う。
 */
public class CraftTreeIgnoreTest
{
    /** fake レシピ: 1 回のクラフトで outputCount 個でき、ingredients を消費する。 */
    private record Recipe(int outputCount, Map<Item, Integer> ingredients) {}

    /** bootstrap 前に {@code Items} へ触れない (registry 未初期化で落ちる) よう {@link #bootstrap} で構築する。 */
    private static Map<Item, Recipe> RECIPES;

    @BeforeAll
    static void bootstrap()
    {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        RECIPES = Map.of(
                Items.OAK_TRAPDOOR,   new Recipe(2, Map.of(Items.OAK_PLANKS, 6)),
                Items.OAK_SIGN,       new Recipe(3, Map.of(Items.OAK_PLANKS, 6, Items.STICK, 1)),
                Items.OAK_FENCE_GATE, new Recipe(1, Map.of(Items.OAK_PLANKS, 2, Items.STICK, 4)),
                Items.OAK_PLANKS,     new Recipe(4, Map.of(Items.OAK_LOG, 1)),
                Items.STICK,          new Recipe(4, Map.of(Items.OAK_PLANKS, 2)));
    }

    /** fake レシピで再帰展開する Decomposer (MaterialListJsonBase と同じく craft 数は切り上げ)。 */
    private static CraftNode decompose(Holder<Item> item, int count, @Nullable Holder<Item> parent, int depth)
    {
        CraftNode node = new CraftNode(item, count, depth);
        node.parentItem = parent;
        Recipe recipe = RECIPES.get(item.value());

        if (recipe != null)
        {
            long crafts = (count + recipe.outputCount() - 1L) / recipe.outputCount();

            for (Map.Entry<Item, Integer> en : recipe.ingredients().entrySet())
            {
                Holder<Item> child = holder(en.getKey());
                node.children.add(decompose(child, (int) (crafts * en.getValue()), item, depth + 1));
            }
        }

        return node;
    }

    private static Holder<Item> holder(Item item)
    {
        return BuiltInRegistries.ITEM.wrapAsHolder(item);
    }

    private static CraftTree.Demand demand(Item item, int count)
    {
        return new CraftTree.Demand(holder(item), count);
    }

    private static CraftTree tree(CraftTree.Demand... demands)
    {
        return new CraftTree(List.of(demands), new CraftTreeState(), CraftTreeIgnoreTest::decompose);
    }

    private static Optional<MatRow> rowOf(List<MatRow> rows, Item item)
    {
        return rows.stream().filter(r -> r.item.value() == item).findFirst();
    }

    /**
     * バグ再現 (#7): 木材需要の源泉 (trapdoor / sign / fence gate) をすべて ignore して
     * expand all した場合、原料 (log / planks / stick) の行は一切出ないこと。
     */
    @Test
    void ignoringAllConsumersDropsRawMaterialDemand()
    {
        CraftTree tree = tree(
                demand(Items.OAK_TRAPDOOR, 6),
                demand(Items.OAK_SIGN, 3),
                demand(Items.OAK_FENCE_GATE, 2));

        tree.ignore(Items.OAK_TRAPDOOR);
        tree.ignore(Items.OAK_SIGN);
        tree.ignore(Items.OAK_FENCE_GATE);
        tree.expandAll();

        List<MatRow> rows = tree.getDisplayRows();
        assertTrue(rows.isEmpty(),
                "全消費元を ignore したら原料需要も消えるべき。残った行: "
                        + rows.stream().map(r -> BuiltInRegistries.ITEM.getKey(r.item.value()) + " x" + r.total).toList());
    }

    /** 一部だけ ignore した場合、残った需要の分だけが原料に載ること。 */
    @Test
    void partialIgnoreKeepsRemainingDemand()
    {
        CraftTree tree = tree(
                demand(Items.OAK_TRAPDOOR, 6),
                demand(Items.OAK_SIGN, 3),
                demand(Items.OAK_FENCE_GATE, 2));

        // ignore なしの全展開: planks 34 (trapdoor 18 + sign 6 + gate 4 + stick 6) -> log 9。
        tree.expandAll();
        assertEquals(9, rowOf(tree.getDisplayRows(), Items.OAK_LOG).orElseThrow().total);

        // trapdoor を ignore: planks 16 (sign 6 + gate 4 + stick 6) -> log 4。
        tree.ignore(Items.OAK_TRAPDOOR);
        List<MatRow> rows = tree.getDisplayRows();
        assertEquals(4, rowOf(rows, Items.OAK_LOG).orElseThrow().total);
        assertTrue(rowOf(rows, Items.OAK_TRAPDOOR).isEmpty());
    }

    /** 回帰確認: 未展開の frontier 行の ignore は従来どおり行を消し、clear ignored で戻る。 */
    @Test
    void frontierIgnoreHidesRowAndClearRestores()
    {
        CraftTree tree = tree(
                demand(Items.OAK_TRAPDOOR, 6),
                demand(Items.OAK_SIGN, 3));

        assertEquals(2, tree.getDisplayRows().size());

        tree.ignore(Items.OAK_TRAPDOOR);
        List<MatRow> rows = tree.getDisplayRows();
        assertTrue(rowOf(rows, Items.OAK_TRAPDOOR).isEmpty());
        assertEquals(3, rowOf(rows, Items.OAK_SIGN).orElseThrow().total);

        tree.clearIgnored();
        assertEquals(6, rowOf(tree.getDisplayRows(), Items.OAK_TRAPDOOR).orElseThrow().total);
    }

    /** 中間素材 (planks) を ignore すると、その先 (log) へも需要が伝播しないこと。 */
    @Test
    void ignoringIntermediateStopsPropagationToItsIngredients()
    {
        CraftTree tree = tree(demand(Items.OAK_TRAPDOOR, 6));

        tree.ignore(Items.OAK_PLANKS);
        tree.expandAll();

        List<MatRow> rows = tree.getDisplayRows();
        assertTrue(rowOf(rows, Items.OAK_PLANKS).isEmpty(), "ignore した planks 自身の行は出ない");
        assertTrue(rowOf(rows, Items.OAK_LOG).isEmpty(), "planks を ignore したら log にも伝播しない");
    }

    /** 倍率 + 在庫ネットとの組合せ: ignore 後の需要に対して倍率と在庫差引が正しく効くこと。 */
    @Test
    void multiplierAndStockApplyToNonIgnoredDemand()
    {
        CraftTree tree = tree(
                demand(Items.OAK_SIGN, 3),
                demand(Items.OAK_TRAPDOOR, 6));

        tree.ignore(Items.OAK_TRAPDOOR);
        tree.setMultiplier(2); // sign 6 -> 2 crafts -> planks 12 + stick 2; stick 2 -> planks 2; planks 14 -> log 4
        tree.setAvailableForTest(Items.OAK_LOG, 3);
        tree.expandAll();

        MatRow log = rowOf(tree.getDisplayRows(), Items.OAK_LOG).orElseThrow();
        assertEquals(4, log.total);
        assertEquals(3, log.have);
        assertEquals(1, log.need);
    }

    /** ignore された型に引き当てられていた在庫が、他の型の計算を狂わせないこと。 */
    @Test
    void stockOfIgnoredTypeIsNotConsumed()
    {
        CraftTree tree = tree(
                demand(Items.OAK_TRAPDOOR, 6),
                demand(Items.OAK_SIGN, 3));

        tree.ignore(Items.OAK_TRAPDOOR);
        tree.setAvailableForTest(Items.OAK_PLANKS, 4);
        tree.expandAll();

        // sign 3 -> planks 6 + stick 1; stick 1 -> planks 2; planks 8 - 在庫 4 = 4 -> log 1。
        List<MatRow> rows = tree.getDisplayRows();
        assertEquals(1, rowOf(rows, Items.OAK_LOG).orElseThrow().total);
        assertFalse(rowOf(rows, Items.OAK_TRAPDOOR).isPresent());
    }
}
