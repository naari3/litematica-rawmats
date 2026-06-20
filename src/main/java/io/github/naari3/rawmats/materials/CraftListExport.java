package io.github.naari3.rawmats.materials;

import java.util.List;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import fi.dy.masa.malilib.util.time.TimeFormat;

/**
 * 現在のフラット買い物リストを、コードブロックに貼れるプレーンテキストに整形する (共有用)。
 * multiplier・在庫差引 (need/have)・タグ選択素材・生成日時を含む。
 */
public final class CraftListExport
{
    private CraftListExport() {}

    public static String render(CraftTree tree)
    {
        List<MatRow> rows = tree.getDisplayRows();
        int mult = tree.getMultiplier();

        StringBuilder sb = new StringBuilder();
        sb.append("RawMaterials: ").append(tree.getSourceTitle());

        if (mult > 1)
        {
            sb.append(" (x").append(mult).append(')');
        }

        sb.append('\n');
        sb.append("Generated: ").append(TimeFormat.RFC1123.formatNow()).append('\n');
        sb.append('\n');

        // 数値列の幅を決める (右寄せ)。
        int needW = "Need".length();
        int haveW = "Have".length();

        for (MatRow r : rows)
        {
            needW = Math.max(needW, Integer.toString(r.need).length());
            haveW = Math.max(haveW, Integer.toString(r.have).length());
        }

        sb.append(padLeft("Need", needW)).append("  ").append(padLeft("Have", haveW)).append("  Item\n");
        sb.append("-".repeat(needW)).append("  ").append("-".repeat(haveW)).append("  ").append("-".repeat(24)).append('\n');

        for (MatRow r : rows)
        {
            String name = new ItemStack(r.item).getHoverName().getString();
            sb.append(padLeft(Integer.toString(r.need), needW)).append("  ")
              .append(padLeft(Integer.toString(r.have), haveW)).append("  ")
              .append(name).append('\n');
        }

        Map<TagKey<Item>, Holder<Item>> overrides = tree.getMaterialOverrides();

        if (!overrides.isEmpty())
        {
            sb.append('\n').append("Selected materials:\n");

            for (Map.Entry<TagKey<Item>, Holder<Item>> en : overrides.entrySet())
            {
                String item = new ItemStack(en.getValue()).getHoverName().getString();
                sb.append("  #").append(en.getKey().location()).append(" -> ").append(item).append('\n');
            }
        }

        return sb.toString();
    }

    private static String padLeft(String s, int width)
    {
        int n = width - s.length();
        return n > 0 ? " ".repeat(n) + s : s;
    }
}
