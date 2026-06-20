package io.github.naari3.rawmats;

import net.fabricmc.api.ModInitializer;

import fi.dy.masa.malilib.event.InitializationHandler;

public class RawMaterials implements ModInitializer
{
    @Override
    public void onInitialize()
    {
        // malilib のエコシステムに自分の InitHandler をぶら下げる。
        // registerModHandlers() はゲーム初期化後に呼ばれる。
        InitializationHandler.getInstance().registerInitializationHandler(new InitHandler());
    }
}
