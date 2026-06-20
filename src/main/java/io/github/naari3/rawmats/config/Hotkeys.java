package io.github.naari3.rawmats.config;

import java.util.List;
import com.google.common.collect.ImmutableList;

import fi.dy.masa.malilib.config.options.ConfigHotkey;
import fi.dy.masa.malilib.hotkeys.IHotkey;

import io.github.naari3.rawmats.Reference;

public class Hotkeys
{
    private static final String HOTKEYS_KEY = Reference.MOD_ID + ".config.hotkeys";

    // 現在の Litematica material list を「原材料展開ビュー」で開く hotkey。
    // 既定は M,K (M,L=material list の隣)。M,R は litematica の toggleAllRendering と衝突するため避ける。
    public static final ConfigHotkey OPEN_RAW_MATERIAL_LIST =
            new ConfigHotkey("openRawMaterialList", "M,K").apply(HOTKEYS_KEY);

    public static final List<IHotkey> HOTKEY_LIST = ImmutableList.of(
            OPEN_RAW_MATERIAL_LIST
    );
}
