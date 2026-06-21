package io.github.naari3.rawmats;

import fi.dy.masa.malilib.config.ConfigManager;
import fi.dy.masa.malilib.event.InputEventHandler;
import fi.dy.masa.malilib.interfaces.IInitializationHandler;
import fi.dy.masa.malilib.registry.Registry;
import fi.dy.masa.malilib.util.data.ModInfo;

import io.github.naari3.rawmats.config.Configs;
import io.github.naari3.rawmats.event.InputHandler;
import io.github.naari3.rawmats.event.KeyCallbacks;
import io.github.naari3.rawmats.gui.GuiConfigs;

public class InitHandler implements IInitializationHandler
{
    @Override
    public void registerModHandlers()
    {
        // config (hotkey の永続化用)
        ConfigManager.getInstance().registerConfigHandler(Reference.MOD_ID, new Configs());

        // config 画面の登録 (malilib の config 切替ドロップダウン / ModMenu からも開ける)。
        Registry.CONFIG_SCREEN.registerConfigScreenFactory(
                new ModInfo(Reference.MOD_ID, Reference.MOD_NAME, GuiConfigs::new));

        // hotkey の登録
        InputEventHandler.getKeybindManager().registerKeybindProvider(InputHandler.getInstance());

        // hotkey のコールバック紐付け
        KeyCallbacks.init();
    }
}
