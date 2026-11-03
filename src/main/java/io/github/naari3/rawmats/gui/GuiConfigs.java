package io.github.naari3.rawmats.gui;

import java.util.ArrayList;
import java.util.List;

import fi.dy.masa.malilib.gui.GuiConfigsBase;

import io.github.naari3.rawmats.Reference;
import io.github.naari3.rawmats.config.Configs;
import io.github.naari3.rawmats.config.Hotkeys;

/**
 * RawMaterials の設定画面。一般設定とホットキーのリバインド。
 * 保存・キーバインド反映は {@link GuiConfigsBase} が removed()/onSettingsChanged() で処理する。
 */
public class GuiConfigs extends GuiConfigsBase
{
    public GuiConfigs()
    {
        super(10, 50, Reference.MOD_ID, null, "rawmats.gui.title.configs");
    }

    @Override
    public void initGui()
    {
        super.initGui();
        this.clearOptions();
    }

    @Override
    public List<ConfigOptionWrapper> getConfigs()
    {
        List<ConfigOptionWrapper> list = new ArrayList<>(ConfigOptionWrapper.createFor(Configs.Generic.OPTIONS));
        list.addAll(ConfigOptionWrapper.createFor(Hotkeys.CONFIG_LIST));
        return list;
    }
}
