package com.example.importsim;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Downloads maps into the saves directory, and kit files into vexbot_kits, on a background thread.
 * Progress is exposed via {@link #progress()} for {@link MapSelectScreen} to render.
 */
public final class ImportTask {

    /** Kit files live here, next to saves/ in the game directory. */
    public static final String KITS_DIR = "vexbot_kits";

    private static final Logger LOG = LoggerFactory.getLogger("import-simulators");
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static volatile String progress = "";
    private static volatile String failureDetail = "";
    private static volatile long totalBytes = 0;
    private static final AtomicLong downloadedBytes = new AtomicLong();

    private ImportTask() {
    }

    public static boolean isRunning() {
        return RUNNING.get();
    }

    public static String progress() {
        return progress;
    }

    /** Why the last failure happened, in words a player can act on. Empty when nothing failed. */
    public static String failureDetail() {
        return failureDetail;
    }

    /** Total bytes to download, or 0 while still being calculated. */
    public static long totalBytes() {
        return totalBytes;
    }

    /** Bytes downloaded so far in the current import. */
    public static long downloadedBytes() {
        return downloadedBytes.get();
    }

    /** Formats a byte count as a human-readable size (e.g. "12.3 MB"). */
    public static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.ROOT, "%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format(Locale.ROOT, "%.1f MB", mb);
        }
        return String.format(Locale.ROOT, "%.2f GB", mb / 1024.0);
    }

    /** True when the map already has a folder in saves, so importing it would re-download it. */
    public static boolean isAlreadyImported(String mapName) {
        return Files.isDirectory(savesDir().resolve(sanitize(mapName)));
    }

    private static Path savesDir() {
        return FabricLoader.getInstance().getGameDir().resolve("saves");
    }

    // ------------------------------------------------------------------ maps

    /**
     * @param returnScreen screen to show once the import finishes (usually the SelectWorldScreen)
     * @param maps         the maps the player ticked
     */
    public static void launch(MinecraftClient client, Screen returnScreen,
                              List<Catalog.MapEntry> maps, Config cfg) {
        if (maps.isEmpty() || !RUNNING.compareAndSet(false, true)) {
            return;
        }
        begin();
        Thread t = new Thread(() -> run(client, returnScreen, maps, cfg), "import-simulators");
        t.setDaemon(true);
        t.start();
    }

    private static void run(MinecraftClient client, Screen returnScreen,
                            List<Catalog.MapEntry> maps, Config cfg) {
        int imported = 0;
        List<String> failed = new ArrayList<>();
        Path saves = savesDir();
        try {
            MapArchive archive = new MapArchive();
            Files.createDirectories(saves);
            Path tmpRoot = saves.resolve(".import-simulators-tmp");
            Files.createDirectories(tmpRoot);

            long size = 0;
            for (Catalog.MapEntry m : maps) {
                size += Math.max(0, m.size());
            }
            totalBytes = size;

            int n = maps.size();
            for (int i = 0; i < n; i++) {
                Catalog.MapEntry map = maps.get(i);
                String safe = sanitize(map.name());
                progress = String.format(Locale.ROOT, "Importing %d/%d: %s", i + 1, n, safe);
                Path stage = tmpRoot.resolve(safe);
                try {
                    // A zip is all or nothing, so a half-finished attempt is never reused.
                    deleteRecursive(stage);
                    archive.downloadInto(map.url(), stage, downloadedBytes::addAndGet);
                    Path world = unwrapSingleFolder(stage);

                    Path target;
                    if (cfg.overwriteExisting) {
                        target = saves.resolve(safe);
                        deleteRecursive(target);
                    } else {
                        target = uniqueDir(saves, safe);
                    }
                    Files.move(world, target);
                    deleteRecursive(stage);
                    imported++;
                    LOG.info("[import-simulators] Imported '{}' -> saves/{}", safe, target.getFileName());
                } catch (Exception e) {
                    failed.add(safe);
                    failureDetail = describe(safe, e);
                    LOG.error("[import-simulators] Failed importing '{}'", safe, e);
                    deleteRecursive(stage);
                }
            }
            if (failed.isEmpty()) {
                deleteRecursive(tmpRoot);
            }
        } catch (Exception e) {
            LOG.error("[import-simulators] Import aborted", e);
            failureDetail = describe("Import aborted", e);
            if (failed.isEmpty()) {
                failed.add("(all)");
            }
        }

        progress = failed.isEmpty()
                ? imported + " map(s) imported"
                : imported + " imported, " + failed.size() + " failed";
        if (!failed.isEmpty()) {
            LOG.warn("[import-simulators] Did not import: {}", String.join(", ", failed));
            LOG.warn("[import-simulators]   saves folder: {}", saves.toAbsolutePath());
        }
        LOG.info("[import-simulators] Done. imported={} failed={}", imported, failed.size());

        finish(client, returnScreen);
    }

    /**
     * A map zip wraps the world in its own folder, so the world to import is that inner folder
     * rather than the extraction directory. Falls back to the directory itself for a flat zip.
     */
    private static Path unwrapSingleFolder(Path dir) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            List<Path> found = entries.toList();
            if (found.size() == 1 && Files.isDirectory(found.get(0))) {
                return found.get(0);
            }
        }
        return dir;
    }

    // ------------------------------------------------------------------ kits

    /**
     * Syncs kit files into the vexbot_kits folder in the game directory, creating it when missing
     * and downloading only the files the player does not already have.
     */
    public static void launchKits(MinecraftClient client, Screen returnScreen, Config cfg) {
        if (!RUNNING.compareAndSet(false, true)) {
            return;
        }
        begin();
        Thread t = new Thread(() -> runKits(client, returnScreen, cfg), "import-simulators-kits");
        t.setDaemon(true);
        t.start();
    }

    private static void runKits(MinecraftClient client, Screen returnScreen, Config cfg) {
        Path kits = FabricLoader.getInstance().getGameDir().resolve(KITS_DIR);
        int added = 0;
        int failed = 0;
        try {
            Files.createDirectories(kits);
            KitMirror mirror = new KitMirror(cfg.kitsMirror);

            progress = "Checking kits…";
            List<Catalog.KitEntry> missing = new ArrayList<>();
            for (Catalog.KitEntry kit : new Catalog(cfg.catalog).kits()) {
                if (!alreadyHave(kits.resolve(sanitize(kit.name())), kit.size())) {
                    missing.add(kit);
                }
            }

            long size = 0;
            for (Catalog.KitEntry kit : missing) {
                size += Math.max(0, kit.size());
            }
            totalBytes = size;

            int n = missing.size();
            for (int i = 0; i < n; i++) {
                Catalog.KitEntry kit = missing.get(i);
                Path dest = kits.resolve(sanitize(kit.name()));
                progress = String.format(Locale.ROOT, "Adding kit %d/%d: %s", i + 1, n, kit.name());
                try {
                    if (!mirror.download(kit.name(), dest, downloadedBytes::addAndGet)) {
                        throw new IOException("not available at " + cfg.kitsMirror);
                    }
                    added++;
                } catch (Exception e) {
                    failed++;
                    failureDetail = describe(kit.name(), e);
                    LOG.error("[import-simulators] Failed downloading kit '{}'", kit.name(), e);
                    deleteQuietly(dest);
                }
            }
        } catch (Exception e) {
            LOG.error("[import-simulators] Kit import aborted", e);
            failed++;
            failureDetail = describe("Could not read the kit list", e);
        }

        if (failed > 0) {
            progress = added + " kit(s) added, " + failed + " failed";
            LOG.warn("[import-simulators] Kits folder: {}", kits.toAbsolutePath());
        } else {
            progress = added == 0 ? "Kits already up to date" : added + " kit(s) added";
        }
        LOG.info("[import-simulators] Kits done. added={} failed={}", added, failed);

        finish(client, returnScreen);
    }

    // ------------------------------------------------------------------ shared

    private static void begin() {
        progress = "Starting…";
        failureDetail = "";
        totalBytes = 0;
        downloadedBytes.set(0);
    }

    private static String describe(String what, Exception e) {
        String msg = e.getMessage() == null ? e.toString() : e.getMessage();
        return what + " — " + msg;
    }

    private static void finish(MinecraftClient client, Screen returnScreen) {
        client.execute(() -> {
            RUNNING.set(false);
            if (returnScreen == null) {
                return;
            }
            if (returnScreen instanceof SelectWorldScreen) {
                // Leaving the world list closed every world's icon, and re-initialising that same
                // screen hands the icons and the cached world list straight back from the widget
                // it was showing before — blank icons, and no sign of what was just imported.
                // A new screen has nothing to inherit, so both are rebuilt from disk.
                client.setScreen(new SelectWorldScreen(new TitleScreen()));
            } else {
                client.setScreen(returnScreen);
            }
        });
    }

    /** True when the file is already on disk, matching the declared size when one was reported. */
    private static boolean alreadyHave(Path file, long declaredSize) {
        try {
            if (!Files.exists(file)) {
                return false;
            }
            return declaredSize <= 0 || Files.size(file) == declaredSize;
        } catch (IOException e) {
            return false;
        }
    }

    static String sanitize(String name) {
        String s = name.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1F]", "_").trim();
        if (s.isEmpty()) {
            return "map";
        }
        if (s.equals(".") || s.equals("..")) {
            return "_" + s;
        }
        return s;
    }

    private static Path uniqueDir(Path parent, String name) {
        Path p = parent.resolve(name);
        int i = 1;
        while (Files.exists(p)) {
            p = parent.resolve(name + " (" + (i++) + ")");
        }
        return p;
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (Exception ignored) {
            // best effort
        }
    }

    private static void deleteRecursive(Path p) {
        if (p == null || !Files.exists(p)) {
            return;
        }
        try (Stream<Path> s = Files.walk(p)) {
            s.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(x -> {
                try {
                    Files.deleteIfExists(x);
                } catch (Exception ignored) {
                    // best effort
                }
            });
        } catch (Exception ignored) {
            // best effort
        }
    }
}
