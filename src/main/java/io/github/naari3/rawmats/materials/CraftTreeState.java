package io.github.naari3.rawmats.materials;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

import fi.dy.masa.malilib.util.data.json.JsonUtils;

/**
 * {@link CraftTree} のセッション内ビュー状態 (source から分離して保持する部分)。
 *
 * - {@link #expanded}: 展開した単位 ({@link Mat#id()}) の集合。
 * - {@link #materialOverride}: タグ材料の置換選択 (集合 ID -> 具体素材)。無い集合は「任意」。
 * - {@link #multiplier}: 倍率 (Litematica 本家と同様。1 = 等倍)。
 * - {@link #ignored}: 行から除外 (ignore) した単位 ({@link Mat#id()}) の集合。
 * - {@link #sortColumn} / {@link #sortReverse}: 並べ替え列・昇降。
 * - {@link #hideAvailable}: 在庫充足行を隠すか。
 *
 * これらは {@link CraftTreeStore} に source キーごとに保持され、同じ material list を開き直すと復元される。
 * {@link #toJson} / {@link #fromJson} で JSON 化し、{@link CraftTreeStore} がディスクへ永続する
 * (Litematica 本家の {@code MaterialListBase.toJson} と同形式)。単位は {@link Mat#id()} の文字列で保存する。
 * 0.4 以前のファイル (アイテム ID の配列、override のキーは {@code #} 無しのタグ ID) もそのまま読める。
 */
public class CraftTreeState
{
    public final Set<String> expanded = new LinkedHashSet<>();
    public final Map<String, Holder<Item>> materialOverride = new HashMap<>();
    public int multiplier = 1;
    public final Set<String> ignored = new LinkedHashSet<>();
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
        obj.add("expanded", idsToJson(this.expanded));
        obj.add("ignored", idsToJson(this.ignored));

        JsonObject overrides = new JsonObject();

        for (Map.Entry<String, Holder<Item>> en : this.materialOverride.entrySet())
        {
            Identifier itemId = BuiltInRegistries.ITEM.getKey(en.getValue().value());

            if (itemId != null)
            {
                // タグは 0.4 以前と同じく # 無しのタグ ID で書く (集合 {..} はそのまま)。
                String key = en.getKey().startsWith("#") ? en.getKey().substring(1) : en.getKey();
                overrides.addProperty(key, itemId.toString());
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

        readIdsInto(obj, "expanded", state.expanded);
        readIdsInto(obj, "ignored", state.ignored);

        if (JsonUtils.hasObject(obj, "material_overrides"))
        {
            for (Map.Entry<String, JsonElement> en : obj.getAsJsonObject("material_overrides").entrySet())
            {
                String key = en.getKey();
                Item item = en.getValue().isJsonPrimitive() ? parseItem(en.getValue().getAsString()) : null;

                if (!key.startsWith("{"))
                {
                    Identifier tagId = Identifier.tryParse(key.startsWith("#") ? key.substring(1) : key);
                    key = tagId != null ? "#" + tagId : null;
                }

                if (key != null && item != null)
                {
                    state.materialOverride.put(key, BuiltInRegistries.ITEM.wrapAsHolder(item));
                }
            }
        }

        return state;
    }

    private static JsonArray idsToJson(Set<String> ids)
    {
        JsonArray arr = new JsonArray();

        for (String id : ids)
        {
            arr.add(id);
        }

        return arr;
    }

    /**
     * 単位 ID の配列を読んで dest に入れる。アイテム ID は解決できたものだけ (未知 ID = mod 構成変更等はスキップ)、
     * 「任意」の ID (#tag / {..}) はそのまま。
     */
    private static void readIdsInto(JsonObject obj, String key, Set<String> dest)
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

            String id = el.getAsString();

            if (id.startsWith("#") || id.startsWith("{"))
            {
                dest.add(id);
                continue;
            }

            Item item = parseItem(id);

            if (item != null)
            {
                dest.add(BuiltInRegistries.ITEM.getKey(item).toString());
            }
        }
    }

    @Nullable
    static Item parseItem(String idStr)
    {
        Identifier id = Identifier.tryParse(idStr);
        return id != null ? BuiltInRegistries.ITEM.getOptional(id).orElse(null) : null;
    }
}
