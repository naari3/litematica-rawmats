package io.github.naari3.rawmats.materials;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import fi.dy.masa.malilib.util.time.TimeFormat;

/**
 * ベイク済みクラフトツリーを Graphviz DOT (有向グラフ) に書き出す。
 *
 * 展開木をそのまま描くのではなく、アイテム種別で 1 ノードにまとめた DAG にする
 * (stick のような共有素材が 1 ノードに集まり、dot の階層レイアウトで家系図状になる)。
 * - ノード = アイテム種別。ラベルは表示名 + 総需要 (全出現の合算、在庫差引なし)。
 * - 辺 = 製品 -> 材料。ラベルはその製品のために必要な材料の総数。
 * - root (schematic 直材) は金色 + 太枠、葉 (レシピ無し = 集める素材) は青、タグ置換可能は破線。
 * - 型グラフには cycle がありうる (stone <-> cobblestone) が、dot は cycle も描けるのでそのまま出す。
 *
 * レンダリング例: dot -Tsvg craft_graph_XXX.dot -o graph.svg
 */
public final class CraftDotExport
{
    private CraftDotExport() {}

    public static String render(CraftTree tree)
    {
        // アイテム種別ごとの総需要、辺 (親種別 -> 子種別) ごとの総需要、root / タグ置換可能を集計する。
        Map<Item, Long> totals = new LinkedHashMap<>();
        Map<Item, Map<Item, Long>> edges = new LinkedHashMap<>();
        Set<Item> rootItems = new LinkedHashSet<>();
        Map<Item, TagKey<Item>> choosable = new HashMap<>();

        for (CraftNode r : tree.getRoots())
        {
            rootItems.add(r.item.value());
            collect(r, totals, edges, choosable);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("// RawMaterials craft graph: ").append(tree.getSourceTitle());

        if (tree.getMultiplier() > 1)
        {
            sb.append(" (x").append(tree.getMultiplier()).append(')');
        }

        sb.append('\n');
        sb.append("// Generated: ").append(TimeFormat.RFC1123.formatNow()).append('\n');
        sb.append("// Render: dot -Tsvg <this file> -o graph.svg\n");
        sb.append("digraph craft {\n");
        sb.append("    rankdir=TB;\n");
        sb.append("    node [shape=box, style=\"rounded,filled\", fillcolor=\"white\", fontname=\"sans-serif\"];\n");
        sb.append("    edge [fontname=\"sans-serif\", fontsize=10];\n\n");

        for (Map.Entry<Item, Long> en : totals.entrySet())
        {
            Item it = en.getKey();
            String id = BuiltInRegistries.ITEM.getKey(it).toString();
            String name = new ItemStack(it).getHoverName().getString();
            boolean leaf = !edges.containsKey(it);
            TagKey<Item> tag = choosable.get(it);

            StringBuilder label = new StringBuilder(escape(name)).append("\\nx").append(en.getValue());

            if (tag != null)
            {
                label.append("\\n#").append(escape(tag.location().toString()));
            }

            sb.append("    \"").append(escape(id)).append("\" [label=\"").append(label).append('"');

            if (leaf)
            {
                sb.append(", fillcolor=\"lightblue\"");
            }
            else if (rootItems.contains(it))
            {
                sb.append(", fillcolor=\"lightgoldenrod1\"");
            }

            if (rootItems.contains(it))
            {
                sb.append(", penwidth=2");
            }

            if (tag != null)
            {
                sb.append(", style=\"rounded,filled,dashed\"");
            }

            sb.append("];\n");
        }

        sb.append('\n');

        for (Map.Entry<Item, Map<Item, Long>> en : edges.entrySet())
        {
            String from = BuiltInRegistries.ITEM.getKey(en.getKey()).toString();

            for (Map.Entry<Item, Long> e : en.getValue().entrySet())
            {
                String to = BuiltInRegistries.ITEM.getKey(e.getKey()).toString();
                sb.append("    \"").append(escape(from)).append("\" -> \"").append(escape(to))
                  .append("\" [label=\"").append(e.getValue()).append("\"];\n");
            }
        }

        sb.append("}\n");
        return sb.toString();
    }

    private static void collect(CraftNode n, Map<Item, Long> totals,
            Map<Item, Map<Item, Long>> edges, Map<Item, TagKey<Item>> choosable)
    {
        Item it = n.item.value();
        totals.merge(it, (long) n.count, Long::sum);

        if (n.isChoosable() && n.choiceKey != null)
        {
            choosable.putIfAbsent(it, n.choiceKey);
        }

        for (CraftNode c : n.children)
        {
            edges.computeIfAbsent(it, k -> new LinkedHashMap<>())
                 .merge(c.item.value(), (long) c.count, Long::sum);
            collect(c, totals, edges, choosable);
        }
    }

    /** DOT のダブルクォート文字列用エスケープ (バックスラッシュと二重引用符)。 */
    private static String escape(String s)
    {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
