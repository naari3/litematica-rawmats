package io.github.naari3.rawmats.materials;

import javax.annotation.Nullable;
import net.minecraft.core.Holder;
import net.minecraft.world.item.Item;

/**
 * フラット買い物リストの 1 行 (同一単位は合算済み)。
 */
public class MatRow
{
    /** 行の単位 (具体アイテム / タグ材料の「任意」)。 */
    public final Mat mat;
    /** 代表アイテム (レシピ表示・ツールチップのアイコン既定に使う)。 */
    public final Holder<Item> item;
    /** 必要総数 (在庫差引前の gross)。本家の Total 列に相当。 */
    public final int total;
    /** まだ集める必要がある数 (在庫差引後)。0 = 在庫で充足。本家の Missing 列に相当。 */
    public final int need;
    /** 在庫数 (参考表示)。本家の Available 列に相当。 */
    public final int have;
    /** さらに展開 (材料へ分解) できるか。 */
    public final boolean expandable;
    /** 展開中の親へ畳み戻せるか。 */
    public final boolean foldable;
    /** タグ材料由来で、別の具体素材や「任意」に切り替えられるか。 */
    public final boolean choosable;
    /** 素材選択メニューの元になるノード (タグスロットと親タグの情報を持つ)。choosable のときのみ非 null。 */
    @Nullable public final CraftNode choiceNode;

    public MatRow(Mat mat, int total, int need, int have, boolean expandable, boolean foldable,
            @Nullable CraftNode choiceNode)
    {
        this.mat = mat;
        this.item = mat.rep();
        this.total = total;
        this.need = need;
        this.have = have;
        this.expandable = expandable;
        this.foldable = foldable;
        this.choosable = choiceNode != null;
        this.choiceNode = choiceNode;
    }
}
