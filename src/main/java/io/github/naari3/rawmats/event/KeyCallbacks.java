package io.github.naari3.rawmats.event;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.hotkeys.IHotkeyCallback;
import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.hotkeys.KeyAction;
import fi.dy.masa.malilib.util.GuiUtils;
import fi.dy.masa.malilib.util.InfoUtils;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.scheduler.ITask;
import fi.dy.masa.litematica.scheduler.TaskScheduler;
import fi.dy.masa.litematica.scheduler.tasks.TaskCountBlocksBase;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;

import io.github.naari3.rawmats.config.Hotkeys;
import io.github.naari3.rawmats.gui.GuiConfigs;
import io.github.naari3.rawmats.gui.GuiCraftTree;
import io.github.naari3.rawmats.materials.CraftTree;
import io.github.naari3.rawmats.materials.CraftTreeState;
import io.github.naari3.rawmats.materials.CraftTreeStore;

public class KeyCallbacks implements IHotkeyCallback
{
    public static void init()
    {
        KeyCallbacks callback = new KeyCallbacks();
        Hotkeys.OPEN_RAW_MATERIAL_LIST.getKeybind().setCallback(callback);
        Hotkeys.OPEN_CONFIG.getKeybind().setCallback(callback);
    }

    @Override
    public boolean onKeyAction(KeyAction action, IKeybind key)
    {
        Minecraft mc = Minecraft.getInstance();

        // 設定画面を開く (ワールド外でも可)。
        if (key == Hotkeys.OPEN_CONFIG.getKeybind())
        {
            GuiBase.openGui(new GuiConfigs());
            return true;
        }

        if (mc.level == null || mc.player == null)
        {
            return false;
        }

        // M+K は Context.ANY (Hotkeys.ANY_CONTEXT) なので GUI 内でもここに来る。
        // 発火はコンテナ画面 (インベントリ/作業台/チェスト等) に限定し、
        // それ以外の GUI (チャット・設定画面等) では false を返してキー入力を素通しする。
        Screen screen = GuiUtils.getCurrentScreen();

        if (screen != null && !(screen instanceof AbstractContainerScreen<?>))
        {
            return false;
        }

        // 直近に開かれた Litematica material list を元ネタにする。
        // 無ければ選択中の placement から自動生成する (Litematica の M+L と同じ流れ)。
        MaterialListBase source = DataManager.getMaterialList();

        if (source == null)
        {
            SchematicPlacement placement = DataManager.getSchematicPlacementManager().getSelectedSchematicPlacement();

            if (placement == null)
            {
                InfoUtils.showGuiOrInGameMessage(MessageType.ERROR, "rawmats.message.no_material_list");
                return true;
            }

            // 初回はコンストラクタがブロックカウントタスクを予約する (非同期)。
            source = placement.getMaterialList();
            // Litematica 側の M+L でも同じリストが開くよう、直近リストとして記憶させる。
            DataManager.setMaterialList(source);
        }

        // まだ計算前 (JSON 復元直後の placement など) でカウントタスクも走っていなければ自前で予約する。
        if (source.getMaterialsAll().isEmpty() && !hasPendingCountTask())
        {
            source.reCreateMaterialList();
        }

        // 同じ material list を開き直したらセッション内状態 (展開/タグ選択/倍率) を復元する。
        CraftTreeState state = CraftTreeStore.get(source);
        CraftTree tree = new CraftTree(source, state);
        GuiCraftTree gui = new GuiCraftTree(tree);

        // コンテナ画面から開いた場合、Esc で元の画面に戻す (JEI と同じ方式)。
        // 画面切替では AbstractContainerScreen.removed() が呼ばれるだけで close packet は
        // 送られない (onClose() だけが closeContainer() する) ので、menu は生きたまま戻せる。
        gui.setParent(screen);

        if (source.getMaterialsAll().isEmpty())
        {
            // 非同期カウントの完了時に、GUI がまだ開かれていればツリーを作り直して反映する。
            MaterialListBase list = source;
            source.setCompletionListener(() ->
            {
                if (GuiUtils.getCurrentScreen() == gui)
                {
                    tree.rebuild(list);
                    gui.reInit();
                }
            });
        }

        GuiBase.openGui(gui);

        if (source.getMaterialsAll().isEmpty())
        {
            InfoUtils.showGuiOrInGameMessage(MessageType.INFO, "rawmats.message.generating");
        }

        return true;
    }

    /** Litematica のブロックカウントタスク (material list 生成) が client scheduler に残っているか。 */
    private static boolean hasPendingCountTask()
    {
        for (ITask task : TaskScheduler.getInstanceClient().getAllTasks())
        {
            if (task instanceof TaskCountBlocksBase)
            {
                return true;
            }
        }

        return false;
    }
}
