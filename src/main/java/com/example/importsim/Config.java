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
 * folder             - Drive folder URL or bare folder id. Defaults to the folder from the request.
 * googleApiKey       - optional. If set, the mod uses the official Drive API v3 (reliable, handles
 *                      folders with >50 files). If blank, the mod scrapes the public folder page,
 *                      which works with zero setup but can break when Google changes their markup.
 * overwriteExisting  - if true, an existing saves/<name> is deleted before import. If false, the
 *                      map is imported as "<name> (1)", "<name> (2)", ...
 */
public class Config {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public String folder = "https://drive.google.com/drive/folders/1idhELV0qMMFqgJhaJCYEzFeL5wtfekVH";
    public String googleApiKey = "";
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

    /** A shareable folder URL, whatever form {@code folder} was given in. */
    public String folderUrl() {
        String f = folder == null ? "" : folder.trim();
        if (f.startsWith("http")) {
            return f;
        }
        return "https://drive.google.com/drive/folders/" + f;
    }

    /** Extracts the folder id from a full URL or returns the trimmed string unchanged. */
    public String folderId() {
        String f = folder == null ? "" : folder.trim();
        int i = f.indexOf("/folders/");
        if (i >= 0) {
            String rest = f.substring(i + "/folders/".length());
            int q = rest.indexOf('?');
            int s = rest.indexOf('/');
            int end = -1;
            if (q >= 0) end = q;
            if (s >= 0 && (end < 0 || s < end)) end = s;
            return end < 0 ? rest : rest.substring(0, end);
        }
        return f;
    }
}
