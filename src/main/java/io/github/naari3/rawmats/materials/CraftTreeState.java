package io.github.naari3.rawmats.materials;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

import fi.dy.masa.malilib.util.data.json.JsonUtils;

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
 * {@link #toJson} / {@link #fromJson} で JSON 化し、{@link CraftTreeStore} がディスクへ永続する
 * (Litematica 本家の {@code MaterialListBase.toJson} と同形式)。アイテム/タグは登録 ID 文字列で保存する。
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

    public JsonObject toJson()
    {
        JsonObject obj = new JsonObject();
        obj.addProperty("multiplier", this.multiplier);
        obj.addProperty("sort_column", this.sortColumn.name());
        obj.addProperty("sort_reverse", this.sortReverse);
        obj.addProperty("hide_available", this.hideAvailable);
        obj.add("expanded", itemsToJson(this.expanded));
        obj.add("ignored", itemsToJson(this.ignored));

        JsonObject overrides = new JsonObject();

        for (Map.Entry<TagKey<Item>, Holder<Item>> en : this.materialOverride.entrySet())
        {
            Identifier itemId = BuiltInRegistries.ITEM.getKey(en.getValue().value());

            if (itemId != null)
            {
                overrides.addProperty(en.getKey().location().toString(), itemId.toString());
            }
        }

        obj.add("material_overrides", overrides);
        return obj;
    }

    public static CraftTreeState fromJson(JsonObject obj)
    {
        CraftTreeState state = new CraftTreeState();
        state.multiplier = Math.max(1, JsonUtils.getIntegerOrDefault(obj, "multiplier", 1));

        try
        {
            state.sortColumn = CraftTree.SortColumn.valueOf(
                    JsonUtils.getStringOrDefault(obj, "sort_column", CraftTree.SortColumn.TOTAL.name()));
        }
        catch (IllegalArgumentException e)
        {
            state.sortColumn = CraftTree.SortColumn.TOTAL;
        }

        state.sortReverse = JsonUtils.getBooleanOrDefault(obj, "sort_reverse", false);
        state.hideAvailable = JsonUtils.getBooleanOrDefault(obj, "hide_available", false);

        readItemsInto(obj, "expanded", state.expanded);
        readItemsInto(obj, "ignored", state.ignored);

        if (JsonUtils.hasObject(obj, "material_overrides"))
        {
            for (Map.Entry<String, JsonElement> en : obj.getAsJsonObject("material_overrides").entrySet())
            {
                Identifier tagId = Identifier.tryParse(en.getKey());
                Item item = en.getValue().isJsonPrimitive() ? parseItem(en.getValue().getAsString()) : null;

                if (tagId != null && item != null)
                {
                    state.materialOverride.put(
                            TagKey.create(Registries.ITEM, tagId),
                            BuiltInRegistries.ITEM.wrapAsHolder(item));
                }
            }
        }

        return state;
    }

    private static JsonArray itemsToJson(Set<Item> items)
    {
        JsonArray arr = new JsonArray();

        for (Item item : items)
        {
            Identifier id = BuiltInRegistries.ITEM.getKey(item);

            if (id != null)
            {
                arr.add(id.toString());
            }
        }

        return arr;
    }

    /** 登録 ID 文字列の配列を読み、解決できたアイテムだけ dest に入れる (未知 ID = mod 構成変更等はスキップ)。 */
    private static void readItemsInto(JsonObject obj, String key, Set<Item> dest)
    {
        if (!JsonUtils.hasArray(obj, key))
        {
            return;
        }

        for (JsonElement el : obj.getAsJsonArray(key))
        {
            if (!el.isJsonPrimitive())
            {
                continue;
            }

            Item item = parseItem(el.getAsString());

            if (item != null)
            {
                dest.add(item);
            }
        }
    }

    private static Item parseItem(String idStr)
    {
        Identifier id = Identifier.tryParse(idStr);
        return id != null ? BuiltInRegistries.ITEM.getOptional(id).orElse(null) : null;
    }
}
