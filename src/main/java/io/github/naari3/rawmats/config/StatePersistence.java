package io.github.naari3.rawmats.config;

import fi.dy.masa.malilib.config.IConfigHandler;

import io.github.naari3.rawmats.materials.CraftTreeStore;

/**
 * {@link CraftTreeStore} のディスク永続を malilib のライフサイクルに乗せる adapter。
 *
 * malilib は登録された全 {@link IConfigHandler} に対し、ゲーム起動完了時・world 入場時に {@code load()}、
 * world 退出時に {@code save()} を呼ぶ (Litematica 本家の {@code DataManager} と同じタイミング)。
 * hotkey 用の {@link Configs} とは別 modId キーで登録するため両方が個別にライフサイクルを受け取る。
 */
public class StatePersistence implements IConfigHandler
{
    @Override
    public void load()
    {
        CraftTreeStore.loadFromFile();
    }

    @Override
    public void save()
    {
        CraftTreeStore.saveToFile();
    }
}
