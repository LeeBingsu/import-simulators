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
 * Which simulators and kits have appeared in the catalog since the player last looked.
 *
 * The map names they have already been shown live in config/import-simulators-seen.json, and the
 * kit names in config/import-simulators-seen-kits.json; anything in the catalog but not in its
 * file is new. On a first run each file is seeded silently, so a fresh install does not announce
 * everything as new.
 */
public final class NewSimulators {

    private static final Logger LOG = LoggerFactory.getLogger("import-simulators");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final AtomicBoolean CHECKED = new AtomicBoolean(false);

    private static final Tracker MAPS = new Tracker("import-simulators-seen.json");
    private static final Tracker KITS = new Tracker("import-simulators-seen-kits.json");

    private NewSimulators() {
    }

    /** How many simulators the player has not been shown yet. */
    public static int count() {
        return MAPS.unseen.size();
    }

    /** How many kits have been added since the player last imported kits. */
    public static int kitCount() {
        return KITS.unseen.size();
    }

    /** The simulators the player has not been shown yet. */
    public static Set<String> unseenMaps() {
        return MAPS.unseen;
    }

    /** Reads the catalog once per game session and works out what is new. */
    public static void refresh() {
        if (!CHECKED.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(() -> {
            Config cfg = Config.load();
            Catalog catalog = new Catalog(cfg.catalog);
            try {
                Set<String> names = new LinkedHashSet<>();
                for (Catalog.MapEntry map : catalog.maps()) {
                    names.add(map.name());
                }
                MAPS.update(names);
            } catch (Exception e) {
                LOG.warn("[import-simulators] Could not check for new simulators", e);
            }
            try {
                Set<String> names = new LinkedHashSet<>();
                for (Catalog.KitEntry kit : catalog.kits()) {
                    names.add(kit.name());
                }
                KITS.update(names);
            } catch (Exception e) {
                LOG.warn("[import-simulators] Could not check for new kits", e);
            }
        }, "import-simulators-new-check");
        t.setDaemon(true);
        t.start();
    }

    /** The player is looking at the list, so nothing in it counts as new any more. */
    public static void markSeen(Collection<String> names) {
        MAPS.markSeen(names);
    }

    /** These kits are now in the player's kits folder, so they no longer count as new. */
    public static void markKitsSeen(Collection<String> names) {
        KITS.markSeen(names);
    }

    /** What is new in one catalog list, and the file recording what has been seen of it. */
    private static final class Tracker {
        private final String fileName;
        private volatile Set<String> unseen = Set.of();
        private volatile boolean viewed = false;

        Tracker(String fileName) {
            this.fileName = fileName;
        }

        void update(Set<String> catalog) {
            Set<String> seen = readSeen();
            if (seen == null) {
                // Nothing recorded yet: treat what is there now as already known, so the badge
                // means "added since you last looked" rather than "you have never looked".
                writeSeen(catalog);
                return;
            }
            Set<String> fresh = new LinkedHashSet<>(catalog);
            fresh.removeAll(seen);
            // The list may have been opened while this check was still running.
            if (!viewed) {
                unseen = fresh;
            }
        }

        void markSeen(Collection<String> names) {
            viewed = true;
            unseen = Set.of();
            writeSeen(new LinkedHashSet<>(names));
        }

        private Path seenFile() {
            return FabricLoader.getInstance().getConfigDir().resolve(fileName);
        }

        /** @return the recorded names, or null when nothing has been recorded yet. */
        private Set<String> readSeen() {
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

        private void writeSeen(Set<String> names) {
            try {
                Files.writeString(seenFile(), GSON.toJson(names));
            } catch (Exception e) {
                LOG.warn("[import-simulators] Could not record what has been seen in {}", fileName, e);
            }
        }
    }
}
