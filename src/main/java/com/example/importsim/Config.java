package com.example.importsim;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * config/import-simulators.json
 *
 * catalog            - base URL of maps.json and kits.json, which say what is available to
 *                      download and where each file lives.
 * kitsMirror         - base URL the kit files themselves are served from.
 * overwriteExisting  - if true, an existing saves/&lt;name&gt; is deleted before import. If false, the
 *                      map is imported as "&lt;name&gt; (1)", "&lt;name&gt; (2)", ...
 */
public class Config {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public String catalog = "https://raw.githubusercontent.com/LeeBingsu/import-simulators/main/catalog";
    public String kitsMirror = "https://raw.githubusercontent.com/LeeBingsu/import-simulators/main/kits";
    public boolean overwriteExisting = false;

    public static Config load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("import-simulators.json");
        try {
            if (Files.exists(path)) {
                Config c = GSON.fromJson(Files.readString(path), Config.class);
                return c != null ? c : new Config();
            }
            Config def = new Config();
            Files.writeString(path, GSON.toJson(def));
            return def;
        } catch (IOException | RuntimeException e) {
            return new Config();
        }
    }
}
