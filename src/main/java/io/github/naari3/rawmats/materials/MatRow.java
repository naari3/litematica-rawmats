package io.github.naari3.rawmats.materials;

import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.Holder;
import net.minecraft.tags.TagKey;
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
    /** タグ材料 (any planks 等) 由来で、別の具体素材に置換できるか。 */
    public final boolean choosable;
    /** 置換キー (タグ)。choosable のときのみ非 null。 */
    @Nullable public final TagKey<Item> choiceKey;
    /** 置換候補一覧。choosable のときのみ非 null。 */
    @Nullable public final List<Holder<Item>> choices;

    public MatRow(Holder<Item> item, int need, int have, boolean expandable, boolean foldable,
            boolean choosable, @Nullable TagKey<Item> choiceKey, @Nullable List<Holder<Item>> choices)
    {
        this.item = item;
        this.need = need;
        this.have = have;
        this.expandable = expandable;
        this.foldable = foldable;
        this.choosable = choosable;
        this.choiceKey = choiceKey;
        this.choices = choices;
    }
}
