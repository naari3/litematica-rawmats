package io.github.naari3.rawmats.materials;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import io.github.naari3.rawmats.config.Configs;

/**
 * 集計の単位。具体アイテム ({@link Of}) か、タグ材料の「任意」({@link AnyOf})。
 *
 * 行・展開状態・除外・在庫ネット・DAG のノードはすべてこの単位で扱う。
 * 永続化には {@link #id()} の文字列を使う (アイテム = 登録 ID、タグ = {@code #ns:path}、集合 = {@code {a,b,...}})。
 */
public sealed interface Mat permits Mat.Of, Mat.AnyOf
{
    /** レシピ参照・アイコンの既定に使う代表アイテム。 */
    Holder<Item> rep();

    /** 永続化と同一性に使う ID 文字列。 */
    String id();

    /** 表示名。 */
    String displayName();

    /** 表示アイコン。「任意」はメンバーを 1 秒ごとに巡回する (バニラのレシピ本と同じ見せ方)。 */
    Holder<Item> icon();

    boolean contains(Item item);

    static Of of(Holder<Item> item)
    {
        return new Of(item);
    }

    /** 具体アイテム。 */
    record Of(Holder<Item> item) implements Mat
    {
        @Override public Holder<Item> rep()  { return this.item; }
        @Override public Holder<Item> icon() { return this.item; }
        @Override public boolean contains(Item it) { return this.item.value() == it; }

        @Override
        public String id()
        {
            return BuiltInRegistries.ITEM.getKey(this.item.value()).toString();
        }

        @Override
        public String displayName()
        {
            return new ItemStack(this.item).getHoverName().getString();
        }

        @Override
        public boolean equals(Object o)
        {
            return o instanceof Of other && other.item.value() == this.item.value();
        }

        @Override
        public int hashCode()
        {
            return System.identityHashCode(this.item.value());
        }
    }

    /**
     * タグ材料の「任意」。メンバー集合で同一性を判定する (同じ集合なら名前付きタグか直接列挙かを問わない)。
     * {@link #excluded} は「任意」の分解でまとめられなかったメンバー (形がそろわない、竹の板材など)。
     */
    final class AnyOf implements Mat
    {
        @Nullable private final TagKey<Item> tag;
        private final List<Holder<Item>> members;
        private final Set<Item> memberSet;
        private final Holder<Item> rep;
        private final String id;

        public AnyOf(@Nullable TagKey<Item> tag, List<Holder<Item>> members, Holder<Item> rep)
        {
            this.tag = tag;
            this.members = List.copyOf(members);
            this.memberSet = new HashSet<>();

            for (Holder<Item> h : this.members)
            {
                this.memberSet.add(h.value());
            }

            this.rep = this.memberSet.contains(rep.value()) || this.members.isEmpty() ? rep : this.members.getFirst();
            this.id = tag != null ? "#" + tag.location() : setId(this.members);
        }

        private static String setId(List<Holder<Item>> members)
        {
            List<String> ids = new ArrayList<>();

            for (Holder<Item> h : members)
            {
                ids.add(BuiltInRegistries.ITEM.getKey(h.value()).toString());
            }

            ids.sort(null);
            return "{" + String.join(",", ids) + "}";
        }

        @Nullable public TagKey<Item> tag() { return this.tag; }
        public List<Holder<Item>> members() { return this.members; }
        public Set<Item> memberSet()        { return this.memberSet; }
        public int size()                   { return this.members.size(); }

        @Override public Holder<Item> rep() { return this.rep; }
        @Override public String id()        { return this.id; }
        @Override public boolean contains(Item it) { return this.memberSet.contains(it); }

        /** 同じメンバー集合で、代表だけを差し替えたもの。 */
        public AnyOf withRep(Holder<Item> newRep)
        {
            return new AnyOf(this.tag, this.members, newRep);
        }

        @Override
        public Holder<Item> icon()
        {
            if (this.members.size() <= 1)
            {
                return this.rep;
            }

            int idx = (int) ((System.currentTimeMillis() / 1000L) % this.members.size());
            return this.members.get(idx);
        }

        @Override
        public String displayName()
        {
            if (this.tag != null)
            {
                Identifier loc = this.tag.location();
                boolean shorten = Configs.Generic.SHORTEN_MINECRAFT_TAGS.getBooleanValue()
                        && Identifier.DEFAULT_NAMESPACE.equals(loc.getNamespace());
                return "#" + (shorten ? loc.getPath() : loc.toString());
            }

            String repName = new ItemStack(this.rep).getHoverName().getString();
            return this.members.size() > 1 ? repName + " +" + (this.members.size() - 1) : repName;
        }

        @Override
        public boolean equals(Object o)
        {
            return o instanceof AnyOf other && other.memberSet.equals(this.memberSet);
        }

        @Override
        public int hashCode()
        {
            return this.memberSet.hashCode();
        }

        @Override
        public String toString()
        {
            return this.id;
        }
    }
}
