package io.github.naari3.rawmats.materials;

import net.minecraft.core.Holder;
import net.minecraft.world.item.Item;

/**
 * フラット買い物リストの 1 行 (同一アイテムは合算済み)。
 */
public class MatRow
{
    public final Holder<Item> item;
    /** まだ集める必要がある数 (在庫差引後)。0 = 在庫で充足。 */
    public final int need;
    /** 在庫数 (参考表示)。 */
    public final int have;
    /** さらに展開 (材料へ分解) できるか。 */
    public final boolean expandable;
    /** 展開中の親へ畳み戻せるか。 */
    public final boolean foldable;

    public MatRow(Holder<Item> item, int need, int have, boolean expandable, boolean foldable)
    {
        this.item = item;
        this.need = need;
        this.have = have;
        this.expandable = expandable;
        this.foldable = foldable;
    }
}
