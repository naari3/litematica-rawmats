package io.github.naari3.rawmats.materials;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

/**
 * {@link CraftTree} のセッション内ビュー状態 (source から分離して保持する部分)。
 *
 * - {@link #expanded}: 展開したアイテム種別の集合。
 * - {@link #materialOverride}: タグ材料の置換選択 (タグ -> 具体素材)。
 * - {@link #multiplier}: 倍率 (Litematica 本家と同様。1 = 等倍)。
 * - {@link #ignored}: 行から除外 (ignore) したアイテム種別の集合。
 * - {@link #sortColumn} / {@link #sortReverse}: 並べ替え列・昇降。
 * - {@link #hideAvailable}: 在庫充足行を隠すか。
 *
 * これらは {@link CraftTreeStore} に source キーごとに保持され、同じ material list を開き直すと復元される。
 * ディスクには永続しない (MC 再起動で消える)。
 */
public class CraftTreeState
{
    public final Set<Item> expanded = new LinkedHashSet<>();
    public final Map<TagKey<Item>, Holder<Item>> materialOverride = new HashMap<>();
    public int multiplier = 1;
    public final Set<Item> ignored = new LinkedHashSet<>();
    public CraftTree.SortColumn sortColumn = CraftTree.SortColumn.TOTAL;
    public boolean sortReverse = false;
    public boolean hideAvailable = false;
}
