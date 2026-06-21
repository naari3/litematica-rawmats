package io.github.naari3.rawmats.event;

import net.minecraft.client.Minecraft;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.hotkeys.IHotkeyCallback;
import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.hotkeys.KeyAction;
import fi.dy.masa.malilib.util.InfoUtils;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialListBase;

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

        // 直近に開かれた Litematica material list を元ネタにする。
        MaterialListBase source = DataManager.getMaterialList();

        if (source == null)
        {
            InfoUtils.showGuiOrInGameMessage(MessageType.ERROR, "rawmats.message.no_material_list");
            return true;
        }

        // 同じ material list を開き直したらセッション内状態 (展開/タグ選択/倍率) を復元する。
        CraftTreeState state = CraftTreeStore.get(source);
        CraftTree tree = new CraftTree(source, state);
        GuiBase.openGui(new GuiCraftTree(tree));
        return true;
    }
}
