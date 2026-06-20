package io.github.naari3.rawmats;

import fi.dy.masa.malilib.config.ConfigManager;
import fi.dy.masa.malilib.event.InputEventHandler;
import fi.dy.masa.malilib.interfaces.IInitializationHandler;

import io.github.naari3.rawmats.config.Configs;
import io.github.naari3.rawmats.event.InputHandler;
import io.github.naari3.rawmats.event.KeyCallbacks;

public class InitHandler implements IInitializationHandler
{
    @Override
    public void registerModHandlers()
    {
        // config (hotkey の永続化用)
        ConfigManager.getInstance().registerConfigHandler(Reference.MOD_ID, new Configs());

        // hotkey の登録
        InputEventHandler.getKeybindManager().registerKeybindProvider(InputHandler.getInstance());

        // hotkey のコールバック紐付け
        KeyCallbacks.init();

        // TODO(v1): malilib の Registry.CONFIG_SCREEN に config 画面を登録すると、
        //   GUI 上で hotkey をリバインドできるようになる (GuiConfigsBase 派生が必要)。
    }
}
