package com.example.importsim;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Which simulators have appeared in the catalog since the player last looked at the list.
 *
 * The names they have already been shown live in config/import-simulators-seen.json; anything in
 * the catalog but not in that file is new. On a first run the file is seeded silently, so a fresh
 * install does not announce all of them as new.
 */
public final class NewSimulators {

    private static final Logger LOG = LoggerFactory.getLogger("import-simulators");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final AtomicBoolean CHECKED = new AtomicBoolean(false);

    private static volatile Set<String> unseen = Set.of();

    private NewSimulators() {
    }

    /** How many simulators the player has not been shown yet. */
    public static int count() {
        return unseen.size();
    }

    public static boolean isNew(String mapName) {
        return unseen.contains(mapName);
    }

    /** Reads the catalog once per game session and works out what is new. */
    public static void refresh() {
        if (!CHECKED.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(() -> {
            try {
                Config cfg = Config.load();
                Set<String> catalog = new LinkedHashSet<>();
                for (Catalog.MapEntry map : new Catalog(cfg.catalog).maps()) {
                    catalog.add(map.name());
                }
                Set<String> seen = readSeen();
                if (seen == null) {
                    // Nothing recorded yet: treat what is there now as already known, so the badge
                    // means "added since you last looked" rather than "you have never looked".
                    writeSeen(catalog);
                    return;
                }
                Set<String> fresh = new LinkedHashSet<>(catalog);
                fresh.removeAll(seen);
                unseen = fresh;
            } catch (Exception e) {
                LOG.warn("[import-simulators] Could not check for new simulators", e);
            }
        }, "import-simulators-new-check");
        t.setDaemon(true);
        t.start();
    }

    /** The player is looking at the list, so nothing in it counts as new any more. */
    public static void markSeen(Collection<String> names) {
        unseen = Set.of();
        writeSeen(new LinkedHashSet<>(names));
    }

    private static Path seenFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("import-simulators-seen.json");
    }

    /** @return the recorded names, or null when nothing has been recorded yet. */
    private static Set<String> readSeen() {
        Path path = seenFile();
        if (!Files.exists(path)) {
            return null;
        }
        try {
            List<String> names = GSON.fromJson(Files.readString(path),
                    new TypeToken<List<String>>() { }.getType());
            return names == null ? Set.of() : new LinkedHashSet<>(names);
        } catch (Exception e) {
            LOG.warn("[import-simulators] Could not read {}; treating it as empty", path, e);
            return Set.of();
        }
    }

    private static void writeSeen(Set<String> names) {
        try {
            Files.writeString(seenFile(), GSON.toJson(names));
        } catch (Exception e) {
            LOG.warn("[import-simulators] Could not record which simulators have been seen", e);
        }
    }
}
