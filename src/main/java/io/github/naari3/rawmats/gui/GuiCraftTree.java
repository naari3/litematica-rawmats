package io.github.naari3.rawmats.gui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiListBase;
import fi.dy.masa.malilib.gui.GuiTextFieldInteger;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.ButtonOnOff;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.gui.interfaces.ITextFieldListener;
import fi.dy.masa.malilib.gui.wrappers.TextFieldType;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.util.FileUtils;
import fi.dy.masa.malilib.util.KeyCodes;
import fi.dy.masa.malilib.util.StringUtils;
import fi.dy.masa.malilib.util.time.TimeFormat;

import io.github.naari3.rawmats.Reference;
import io.github.naari3.rawmats.materials.CraftDotExport;
import io.github.naari3.rawmats.materials.CraftListExport;
import io.github.naari3.rawmats.materials.CraftTree;
import io.github.naari3.rawmats.materials.MatRow;

public class GuiCraftTree extends GuiListBase<MatRow, WidgetCraftTreeEntry, WidgetListCraftTree>
{
    private final CraftTree tree;
    @Nullable private WidgetCraftMenu popup;
    /** 「除外中」ボタンの位置 (除外一覧メニューのアンカー)。 */
    private int ignoredBtnX, ignoredBtnY;
    /** Tab 長押しレシピ表示の lookup キャッシュ (recipe book 全走査を毎フレームしないため)。 */
    private final Map<Item, Optional<RecipeTooltip>> recipeCache = new HashMap<>();
    public GuiCraftTree(CraftTree tree)
    {
        super(10, 46);

        this.tree = tree;
        this.title = StringUtils.translate("rawmats.gui.title.raw_material_list");
        this.useTitleHierarchy = false;
    }

    public CraftTree getTree()
    {
        return this.tree;
    }

    /** item のレシピツールチップ (Tab 長押し表示用)。レシピが無ければ null。 */
    @Nullable
    public RecipeTooltip getRecipeTooltip(Holder<Item> item)
    {
        return this.recipeCache
                .computeIfAbsent(item.value(), it -> Optional.ofNullable(RecipeTooltip.lookup(item)))
                .orElse(null);
    }

    public void reInit()
    {
        this.initGui();
        if (this.getListWidget() != null)
        {
            this.getListWidget().refreshEntries();
        }
    }

    /** 左クリック: 展開 (展開は常に単一動作なので即実行)。 */
    public void expandRow(MatRow row)
    {
        this.tree.expand(row.item.value());
        this.reInit();
    }

    /** 行の除外 (ignore): リストから消す。Clear ignored で戻せる。 */
    public void ignoreRow(MatRow row)
    {
        this.tree.ignore(row.item.value());
        this.reInit();
    }

    /** 右クリック: 親へ畳む。候補が 1 つなら即実行、複数のときだけアイコンポップアップで尋ねる。 */
    public void foldRow(MatRow row, int mouseX, int mouseY)
    {
        List<Item> candidates = this.tree.getCollapseCandidates(row.item.value());

        if (candidates.isEmpty())
        {
            return;
        }

        if (candidates.size() == 1)
        {
            this.tree.collapse(candidates.get(0));
            this.reInit();
            return;
        }

        List<WidgetCraftMenu.Entry> entries = new ArrayList<>();
        for (Item parent : candidates)
        {
            Holder<Item> holder = BuiltInRegistries.ITEM.wrapAsHolder(parent);
            entries.add(new WidgetCraftMenu.Entry(holder, () -> this.tree.collapse(parent)));
        }

        this.openPopup(entries, mouseX, mouseY);
    }

    /** 中クリック: タグ材料 (any planks 等) の具体素材を選び直す。候補をアイコンポップアップで提示。 */
    public void chooseRow(MatRow row, int mouseX, int mouseY)
    {
        if (!row.choosable || row.choiceKey == null || row.choices == null)
        {
            return;
        }

        List<WidgetCraftMenu.Entry> entries = new ArrayList<>();
        for (Holder<Item> cand : row.choices)
        {
            entries.add(new WidgetCraftMenu.Entry(cand, () -> this.tree.chooseMaterial(row.choiceKey, cand)));
        }

        this.openPopup(entries, mouseX, mouseY);
    }

    /** ポップアップメニューを画面内にクランプして表示する。 */
    private void openPopup(List<WidgetCraftMenu.Entry> entries, int mouseX, int mouseY)
    {
        WidgetCraftMenu menu = new WidgetCraftMenu(mouseX, mouseY, entries, this::onMenuActionDone);
        int px = Math.min(mouseX, this.getScreenWidth() - menu.getWidth() - 4);
        int py = Math.min(mouseY, this.getScreenHeight() - menu.getHeight() - 4);

        if (px != mouseX || py != mouseY)
        {
            menu = new WidgetCraftMenu(Math.max(2, px), Math.max(2, py), entries, this::onMenuActionDone);
        }

        this.popup = menu;
    }

    public boolean hasPopup()
    {
        return this.popup != null;
    }

    /**
     * 除外中アイテムの一覧メニュー (「除外中」ボタン直下に固定表示)。
     * クリックで個別に解除し、残があればメニューを開き直す (連続解除できる)。
     */
    public void openIgnoredMenu()
    {
        List<Item> ignored = this.tree.getIgnored();

        if (ignored.isEmpty())
        {
            this.popup = null;
            return;
        }

        List<WidgetCraftMenu.Entry> entries = new ArrayList<>();

        for (Item it : ignored)
        {
            Holder<Item> holder = BuiltInRegistries.ITEM.wrapAsHolder(it);
            entries.add(new WidgetCraftMenu.Entry(holder, () -> this.tree.unignore(it)));
        }

        this.popup = new WidgetCraftMenu(this.ignoredBtnX, this.ignoredBtnY + 22, entries, this::onIgnoredMenuDone);
    }

    private void onIgnoredMenuDone()
    {
        this.reInit(); // ボタン (除外数) と行を更新。popup も一旦消える
        this.openIgnoredMenu(); // 残があれば開き直す
    }

    public void onMenuActionDone()
    {
        this.popup = null;
        this.reInit();
    }

    /** 現在の買い物リストを Litematica config フォルダ配下にプレーンテキストで書き出し、チャットに開けるリンクを出す。 */
    public void exportToFile()
    {
        this.writeExportFile("raw_material_list_", ".txt", CraftListExport.render(this.tree));
    }

    /** レシピツリー画面 (左→右の家系図表示) を開く。Esc でこのリストに戻る。 */
    public void openGraph()
    {
        GuiCraftGraph gui = new GuiCraftGraph(this.tree);
        gui.setParent(this);
        GuiBase.openGui(gui);
    }

    /** クラフトフロー (種別マージ DAG のグラフ) を開く。リスト表示 (手順) とは画面内で相互切替。 */
    public void openSteps()
    {
        GuiCraftDag gui = new GuiCraftDag(this.tree);
        gui.setParent(this);
        GuiBase.openGui(gui);
    }

    /** クラフト依存グラフを Graphviz DOT で書き出す (dot -Tsvg で画像化する用)。 */
    public void exportDotToFile()
    {
        this.writeExportFile("craft_graph_", ".dot", CraftDotExport.render(this.tree));
    }

    private void writeExportFile(String prefix, String ext, String content)
    {
        Path dir = FileUtils.getConfigDirectory().resolve(Reference.MOD_ID);
        Path file = dir.resolve(prefix + TimeFormat.REGULAR.formatNow() + ext);

        try
        {
            Files.createDirectories(dir);
            Files.writeString(file, content);
        }
        catch (IOException e)
        {
            this.addMessage(MessageType.ERROR, "rawmats.message.export_failed", e.getMessage());
            return;
        }

        String key = "rawmats.message.exported";
        this.addMessage(MessageType.SUCCESS, key, file.getFileName().toString());

        Minecraft mc = Minecraft.getInstance();

        if (mc.player != null)
        {
            StringUtils.sendOpenFileChatMessage(mc.player, key, file);
        }
    }

    @Override
    protected int getBrowserWidth()
    {
        return this.getScreenWidth() - 20;
    }

    @Override
    protected int getBrowserHeight()
    {
        return this.getScreenHeight() - 80;
    }

    @Override
    public void initGui()
    {
        // コンテナ画面から開いた直後 (作業グリッドの中身が返却された後) でも在庫数が正しくなるよう、
        // 画面 (再) 構築のたびにインベントリを読み直す。
        this.tree.refreshAvailable();
        super.initGui();
        this.popup = null;

        int x = 12;
        int y = 24;
        int gap = 2;

        x += this.addButton(x, y, ButtonType.COLLAPSE_ALL) + gap;
        x += this.addButton(x, y, ButtonType.EXPAND_ALL) + gap;
        x += this.addToggle(x, y, ButtonType.HIDE_AVAILABLE, this.tree.getHideAvailable()) + gap;

        if (this.tree.hasIgnored())
        {
            x += this.addButton(x, y, ButtonType.CLEAR_IGNORED) + gap;

            // 除外中の一覧メニューを開くボタン。メニューのアンカーに位置を控える。
            String label = StringUtils.translate("rawmats.gui.button.ignored", this.tree.getIgnored().size());
            ButtonGeneric ignored = new ButtonGeneric(x, y, -1, 20, label);
            this.ignoredBtnX = x;
            this.ignoredBtnY = y;
            x += this.addButton(ignored, new Listener(ButtonType.IGNORED, this)).getWidth() + gap;
        }

        // 本家 GuiMaterialList に寄せて右上に倍率入力を置く。
        String label = StringUtils.translate("rawmats.gui.label.multiplier");
        int w = this.getStringWidth(label);
        this.addLabel(this.getScreenWidth() - w - 56, y + 5, w, 12, 0xFFFFFFFF, label);

        GuiTextFieldInteger tf = new GuiTextFieldInteger(this.getScreenWidth() - 52, y + 2, 40, 16, this.font);
        tf.setValueWrapper(String.valueOf(this.tree.getMultiplier()));
        this.addTextField(tf, new MultiplierListener(this), TextFieldType.STRING);

        // 下部: 書き出しボタン + 集計情報 (本家 GuiMaterialList の下部レイアウトに寄せる)。
        int by = this.getScreenHeight() - 26;
        int bx = 12;
        bx += this.addButton(bx, by, ButtonType.EXPORT) + gap;
        bx += this.addButton(bx, by, ButtonType.EXPORT_DOT) + gap;
        bx += this.addButton(bx, by, ButtonType.GRAPH) + gap;
        bx += this.addButton(bx, by, ButtonType.STEPS) + gap + 6;
        this.addSummaryLabel(bx, by + 6);
    }

    /** 下部に「種類 / 必要数 / 完了率」の集計情報を出す。 */
    private void addSummaryLabel(int x, int y)
    {
        List<MatRow> rows = this.tree.getDisplayRows();
        int types = rows.size();
        long need = 0;
        long total = 0;

        for (MatRow r : rows)
        {
            need += r.need;
            total += r.total;
        }

        double donePct = total > 0 ? (double) (total - need) / (double) total * 100.0 : 100.0;
        String summary = StringUtils.translate("rawmats.gui.label.summary",
                types, need, String.format("%.1f%%", donePct));
        this.addLabel(x, y, this.getStringWidth(summary), 12, 0xFFFFFFFF, summary);
    }

    private int addButton(int x, int y, ButtonType type)
    {
        ButtonGeneric button = new ButtonGeneric(x, y, -1, 20, StringUtils.translate(type.key));
        this.addButton(button, new Listener(type, this));
        return button.getWidth();
    }

    private int addToggle(int x, int y, ButtonType type, boolean isOn)
    {
        ButtonOnOff button = new ButtonOnOff(x, y, -1, false, type.key, isOn);
        this.addButton(button, new Listener(type, this));
        return button.getWidth();
    }

    @Override
    protected WidgetListCraftTree createListWidget(int listX, int listY)
    {
        return new WidgetListCraftTree(listX, listY, this.getBrowserWidth(), this.getBrowserHeight(), this);
    }

    @Override
    public boolean onMouseClicked(MouseButtonEvent click, boolean doubleClick)
    {
        if (this.popup != null)
        {
            if (this.popup.isMouseOver((int) click.x(), (int) click.y()))
            {
                this.popup.onMouseClicked(click, doubleClick);
            }
            else
            {
                this.popup = null;
            }
            return true;
        }

        return super.onMouseClicked(click, doubleClick);
    }

    @Override
    public boolean onKeyTyped(KeyEvent input)
    {
        if (this.popup != null && input.key() == KeyCodes.KEY_ESCAPE)
        {
            this.popup = null;
            return true;
        }

        return super.onKeyTyped(input);
    }

    @Override
    public void drawContents(GuiContext ctx, int mouseX, int mouseY, float partialTicks)
    {
        super.drawContents(ctx, mouseX, mouseY, partialTicks);

        if (this.popup != null)
        {
            this.popup.render(ctx, mouseX, mouseY, false);
        }
    }

    private enum ButtonType
    {
        COLLAPSE_ALL   ("rawmats.gui.button.collapse_all"),
        EXPAND_ALL     ("rawmats.gui.button.expand_all"),
        EXPORT         ("rawmats.gui.button.export"),
        EXPORT_DOT     ("rawmats.gui.button.export_dot"),
        GRAPH          ("rawmats.gui.button.graph"),
        STEPS          ("rawmats.gui.button.steps"),
        HIDE_AVAILABLE ("rawmats.gui.button.hide_available"),
        CLEAR_IGNORED  ("rawmats.gui.button.clear_ignored"),
        IGNORED        ("rawmats.gui.button.ignored");

        private final String key;

        ButtonType(String key)
        {
            this.key = key;
        }
    }

    private record MultiplierListener(GuiCraftTree gui) implements ITextFieldListener<GuiTextFieldInteger>
    {
        @Override
        public boolean onTextChange(GuiTextFieldInteger textField)
        {
            try
            {
                int multiplier = Integer.parseInt(textField.getValueWrapper());

                if (multiplier != this.gui.tree.getMultiplier())
                {
                    this.gui.tree.setMultiplier(multiplier);
                    this.gui.getListWidget().refreshEntries();
                    return true;
                }
            }
            catch (Exception e)
            {
                this.gui.tree.setMultiplier(1);
                this.gui.getListWidget().refreshEntries();
            }

            return false;
        }
    }

    private record Listener(ButtonType type, GuiCraftTree parent) implements IButtonActionListener
    {
        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton)
        {
            switch (this.type)
            {
                case EXPAND_ALL     -> { this.parent.tree.expandAll(); this.parent.tree.logStructure(); }
                case COLLAPSE_ALL   -> this.parent.tree.collapseAll();
                case EXPORT         -> { this.parent.exportToFile(); return; }
                case EXPORT_DOT     -> { this.parent.exportDotToFile(); return; }
                case GRAPH          -> { this.parent.openGraph(); return; }
                case STEPS          -> { this.parent.openSteps(); return; }
                case HIDE_AVAILABLE -> this.parent.tree.toggleHideAvailable();
                case CLEAR_IGNORED  -> this.parent.tree.clearIgnored();
                case IGNORED        -> { this.parent.openIgnoredMenu(); return; }
            }
            this.parent.reInit();
        }
    }
}
