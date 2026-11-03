package io.github.naari3.rawmats.gui;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import fi.dy.masa.malilib.util.StringUtils;

import io.github.naari3.rawmats.materials.CraftNode;
import io.github.naari3.rawmats.materials.CraftTree;
import io.github.naari3.rawmats.materials.Mat;

/**
 * 中クリックの素材選択メニューの中身 (リスト表示と DAG 表示で共通)。
 *
 * - タグスロットの候補: 先頭に「任意」、続いてタグ内の具体アイテム。
 * - 親タグの候補 (展開先の行のみ。例: #planks で crimson_planks を選んだ先の crimson_stem):
 *   親タグの各メンバーに対応する材料 (spruce_log 等) と、親タグの「任意」。選ぶと親タグの選択を変える。
 */
public final class ChoiceMenu
{
    private ChoiceMenu() {}

    public static List<WidgetCraftMenu.Entry> build(CraftTree tree, CraftNode node, Mat current)
    {
        List<WidgetCraftMenu.Entry> entries = new ArrayList<>();
        Mat.AnyOf choice = node.choice;

        if (choice != null && choice.size() > 1)
        {
            entries.add(new WidgetCraftMenu.Entry(choice,
                    StringUtils.translate("rawmats.gui.label.choose_any", choice.displayName()),
                    current.equals(choice), () -> tree.chooseMaterial(choice, null)));

            for (Holder<Item> cand : choice.members())
            {
                entries.add(new WidgetCraftMenu.Entry(Mat.of(cand), name(cand),
                        current.equals(Mat.of(cand)), () -> tree.chooseMaterial(choice, cand)));
            }
        }

        Mat.AnyOf upstream = node.upstream;
        List<CraftTree.UpstreamOption> options = tree.upstreamOptions(node);

        if (upstream != null && options.size() > 1)
        {
            CraftTree.AnyShape shape = node.parentItem != null ? tree.anyShape(upstream.withRep(node.parentItem)) : null;
            Mat anyIcon = shape != null && node.upstreamSlot < shape.slotAny().size() && shape.slotAny().get(node.upstreamSlot) != null
                    ? shape.slotAny().get(node.upstreamSlot)
                    : upstream;
            entries.add(new WidgetCraftMenu.Entry(anyIcon,
                    StringUtils.translate("rawmats.gui.label.choose_any", upstream.displayName()),
                    false, () -> tree.chooseMaterial(upstream, null)));

            for (CraftTree.UpstreamOption opt : options)
            {
                boolean sel = node.parentItem != null && opt.member().value() == node.parentItem.value();
                String label = StringUtils.translate("rawmats.gui.label.choose_upstream", name(opt.slotItem()), name(opt.member()));
                entries.add(new WidgetCraftMenu.Entry(Mat.of(opt.slotItem()), label, sel,
                        () -> tree.chooseMaterial(upstream, opt.member())));
            }
        }

        return entries;
    }

    private static String name(Holder<Item> item)
    {
        return new ItemStack(item).getHoverName().getString();
    }
}
