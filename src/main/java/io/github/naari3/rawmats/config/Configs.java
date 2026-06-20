package io.github.naari3.rawmats.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import fi.dy.masa.malilib.config.ConfigUtils;
import fi.dy.masa.malilib.config.IConfigHandler;
import fi.dy.masa.malilib.util.FileUtils;
import fi.dy.masa.malilib.util.data.json.JsonUtils;

import io.github.naari3.rawmats.Reference;

/**
 * Litematica の Configs を踏襲した最小実装 (hotkey の load/save のみ)。
 */
public class Configs implements IConfigHandler
{
    private static final String CONFIG_FILE_NAME = Reference.MOD_ID + ".json";

    @Override
    public void load()
    {
        loadFromFile();
    }

    @Override
    public void save()
    {
        saveToFile();
    }

    public static void loadFromFile()
    {
        Path configFile = FileUtils.getConfigDirectory().resolve(CONFIG_FILE_NAME);

        if (Files.exists(configFile) && Files.isReadable(configFile))
        {
            JsonElement element = JsonUtils.parseJsonFile(configFile);

            if (element != null && element.isJsonObject())
            {
                JsonObject root = element.getAsJsonObject();
                ConfigUtils.readConfigBase(root, "Hotkeys", Hotkeys.HOTKEY_LIST);
            }
        }
    }

    public static void saveToFile()
    {
        Path dir = FileUtils.getConfigDirectory();

        if (!Files.exists(dir))
        {
            try
            {
                Files.createDirectories(dir);
            }
            catch (IOException ignored) { }
        }

        if (Files.isDirectory(dir))
        {
            JsonObject root = new JsonObject();
            ConfigUtils.writeConfigBase(root, "Hotkeys", Hotkeys.HOTKEY_LIST);
            JsonUtils.writeJsonToFile(root, dir.resolve(CONFIG_FILE_NAME));
        }
    }
}
