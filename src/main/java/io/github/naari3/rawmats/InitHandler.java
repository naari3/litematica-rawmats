package io.github.naari3.rawmats;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;

import fi.dy.masa.malilib.config.ConfigManager;
import fi.dy.masa.malilib.event.InputEventHandler;
import fi.dy.masa.malilib.interfaces.IInitializationHandler;
import fi.dy.masa.malilib.registry.Registry;
import fi.dy.masa.malilib.util.data.ModInfo;

import io.github.naari3.rawmats.config.Configs;
import io.github.naari3.rawmats.config.StatePersistence;
import io.github.naari3.rawmats.event.InputHandler;
import io.github.naari3.rawmats.event.KeyCallbacks;
import io.github.naari3.rawmats.gui.GuiConfigs;

public class InitHandler implements IInitializationHandler
{
    @Override
    public void registerModHandlers()
    {
        // 開発・デバッグ用。-Drawmats.debug=true で rawmats logger を DEBUG に切替
        // (Log4j2 の root を触らずローカルに昇格)。runClient (build.gradle) ではこの prop を渡している。
        if (Boolean.getBoolean("rawmats.debug"))
        {
            Configurator.setLevel(Reference.MOD_NAME, Level.DEBUG);
            Reference.LOGGER.info("[rawmats] DEBUG logging enabled via -Drawmats.debug");
        }

        // config (hotkey の永続化用)
        ConfigManager.getInstance().registerConfigHandler(Reference.MOD_ID, new Configs());

        // クラフトツリーのビュー状態 (展開/タグ選択/倍率/ソート/ignore) のディスク永続。
        // hotkey とは別 modId キーで登録 (ConfigManager の map は modId キーで、同一キーは上書きされるため)。
        ConfigManager.getInstance().registerConfigHandler(Reference.MOD_ID + "_state", new StatePersistence());

        // config 画面の登録 (malilib の config 切替ドロップダウン / ModMenu からも開ける)。
        Registry.CONFIG_SCREEN.registerConfigScreenFactory(
                new ModInfo(Reference.MOD_ID, Reference.MOD_NAME, GuiConfigs::new));

        // hotkey の登録
        InputEventHandler.getKeybindManager().registerKeybindProvider(InputHandler.getInstance());

        // hotkey のコールバック紐付け
        KeyCallbacks.init();
    }
}
