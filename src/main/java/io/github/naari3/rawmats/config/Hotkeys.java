package io.github.naari3.rawmats.config;

import java.util.List;
import com.google.common.collect.ImmutableList;

import fi.dy.masa.malilib.config.options.ConfigHotkey;
import fi.dy.masa.malilib.hotkeys.IHotkey;
import fi.dy.masa.malilib.hotkeys.KeyAction;
import fi.dy.masa.malilib.hotkeys.KeybindSettings;

import io.github.naari3.rawmats.Reference;

public class Hotkeys
{
    private static final String HOTKEYS_KEY = Reference.MOD_ID + ".config.hotkeys";

    // KeybindSettings.DEFAULT の Context.ANY 版。GUI 内でも updateIsPressed が通るようにする。
    // どの画面で発火させるかは KeyCallbacks 側で絞る (コンテナ画面のみ。チャット等では素通し)。
    private static final KeybindSettings ANY_CONTEXT =
            KeybindSettings.create(KeybindSettings.Context.ANY, KeyAction.PRESS, false, true, false, true);

    // 現在の Litematica material list を「原材料展開ビュー」で開く hotkey。
    // 既定は M,K (M,L=material list の隣)。M,R は litematica の toggleAllRendering と衝突するため避ける。
    // インベントリ/作業台などのコンテナ画面からも開けるよう Context.ANY にしている。
    public static final ConfigHotkey OPEN_RAW_MATERIAL_LIST =
            new ConfigHotkey("openRawMaterialList", "M,K", ANY_CONTEXT).apply(HOTKEYS_KEY);

    // 設定画面 (ホットキーのリバインド等) を開く hotkey。既定 M,J。
    // M,C は litematica の openGuiSettings (全体設定) と衝突するため避ける。
    public static final ConfigHotkey OPEN_CONFIG =
            new ConfigHotkey("openConfig", "M,J").apply(HOTKEYS_KEY);

    /** keybind provider / 永続化用 (IHotkey として)。 */
    public static final List<IHotkey> HOTKEY_LIST = ImmutableList.of(
            OPEN_RAW_MATERIAL_LIST,
            OPEN_CONFIG
    );

    /** config 画面表示用 (IConfigBase として)。 */
    public static final List<ConfigHotkey> CONFIG_LIST = ImmutableList.of(
            OPEN_RAW_MATERIAL_LIST,
            OPEN_CONFIG
    );
}
