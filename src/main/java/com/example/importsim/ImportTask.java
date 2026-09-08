package com.example.importsim;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Downloads a chosen set of Drive map folders into the saves directory on a background thread.
 * Progress is exposed via {@link #progress()} for {@link MapSelectScreen} to render.
 */
public final class ImportTask {

    private static final Logger LOG = LoggerFactory.getLogger("import-simulators");
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static volatile String progress = "";
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

    /** Total bytes to download across all selected maps, or 0 while still being calculated. */
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

    /**
     * @param returnScreen screen to show once the import finishes (usually the SelectWorldScreen)
     * @param maps         the map folders the player ticked
     */
    public static void launch(MinecraftClient client, Screen returnScreen, List<GDrive.Entry> maps, Config cfg) {
        if (maps.isEmpty() || !RUNNING.compareAndSet(false, true)) {
            return;
        }
        progress = "Starting…";
        totalBytes = 0;
        downloadedBytes.set(0);
        Thread t = new Thread(() -> run(client, returnScreen, maps, cfg), "import-simulators");
        t.setDaemon(true);
        t.start();
    }

    private static void run(MinecraftClient client, Screen returnScreen, List<GDrive.Entry> maps, Config cfg) {
        int imported = 0;
        List<String> failedMaps = new ArrayList<>();
        String folderUrl = cfg.folderUrl();
        Path saves = FabricLoader.getInstance().getGameDir().resolve("saves");
        try {
            GDrive drive = new GDrive(cfg.googleApiKey);
            Files.createDirectories(saves);
            Path tmpRoot = saves.resolve(".import-simulators-tmp");
            deleteRecursive(tmpRoot);
            Files.createDirectories(tmpRoot);

            progress = "Calculating download size…";
            long size = 0;
            for (GDrive.Entry mf : maps) {
                try {
                    size += sumSizes(drive, mf.id());
                } catch (Exception e) {
                    LOG.warn("[import-simulators] Failed to size '{}'; total size may be inaccurate", mf.name(), e);
                }
            }
            totalBytes = size;

            int n = maps.size();
            for (int i = 0; i < n; i++) {
                GDrive.Entry mf = maps.get(i);
                String safe = sanitize(mf.name());
                progress = String.format(Locale.ROOT, "Importing %d/%d: %s", i + 1, n, safe);
                Path stage = tmpRoot.resolve(safe);
                try {
                    downloadFolderRecursive(drive, mf.id(), stage, safe, i + 1, n);

                    Path target;
                    if (cfg.overwriteExisting) {
                        target = saves.resolve(safe);
                        deleteRecursive(target);
                    } else {
                        target = uniqueDir(saves, safe);
                    }
                    Files.move(stage, target);
                    imported++;
                    LOG.info("[import-simulators] Imported '{}' -> saves/{}", safe, target.getFileName());
                } catch (Exception e) {
                    failedMaps.add(safe);
                    LOG.error("[import-simulators] Failed importing '{}'", safe, e);
                    deleteRecursive(stage);
                }
            }
            deleteRecursive(tmpRoot);
        } catch (Exception e) {
            LOG.error("[import-simulators] Import aborted", e);
            if (failedMaps.isEmpty()) {
                failedMaps.add("(all)");
            }
        }

        final int fi = imported;
        final List<String> ff = List.copyOf(failedMaps);
        if (!ff.isEmpty()) {
            announceManual(client, folderUrl, saves, ff);
            progress = fi + " imported, " + ff.size() + " failed — Drive link copied";
        } else {
            progress = fi + " map(s) imported";
        }
        LOG.info("[import-simulators] Done. imported={} failed={}", fi, ff.size());

        client.execute(() -> {
            RUNNING.set(false);
            if (returnScreen != null) {
                client.setScreen(returnScreen);
                if (returnScreen instanceof SelectWorldScreen && returnScreen == client.currentScreen) {
                    // Re-run init() so the world list picks up the new folders.
                    returnScreen.resize(returnScreen.width, returnScreen.height);
                }
            }
        });
    }

    /** Copy the Drive folder link to the clipboard and log what to grab by hand. */
    private static void announceManual(MinecraftClient client, String folderUrl, Path saves, List<String> failedMaps) {
        try {
            client.keyboard.setClipboard(folderUrl);
        } catch (Throwable ignored) {
            // clipboard is best-effort
        }
        LOG.warn("[import-simulators] Some maps did not import. Download them by hand:");
        LOG.warn("[import-simulators]   Drive folder : {}  (copied to clipboard)", folderUrl);
        LOG.warn("[import-simulators]   Put them in  : {}", saves.toAbsolutePath());
        LOG.warn("[import-simulators]   Missing maps : {}", String.join(", ", failedMaps));
    }

    private static void downloadFolderRecursive(GDrive drive, String folderId, Path dir,
                                                String label, int idx, int total) throws Exception {
        Files.createDirectories(dir);
        for (GDrive.Entry e : drive.listFolder(folderId)) {
            String safe = sanitize(e.name());
            Path child = dir.resolve(safe);
            if (e.isFolder()) {
                downloadFolderRecursive(drive, e.id(), child, label, idx, total);
            } else {
                progress = String.format(Locale.ROOT, "Importing %d/%d: %s — %s", idx, total, label, safe);
                drive.downloadFile(e.id(), child, downloadedBytes::addAndGet);
            }
        }
    }

    /** Recursively sums declared file sizes under a Drive folder, without downloading anything. */
    private static long sumSizes(GDrive drive, String folderId) throws Exception {
        long total = 0;
        for (GDrive.Entry e : drive.listFolder(folderId)) {
            total += e.isFolder() ? sumSizes(drive, e.id()) : Math.max(0, e.size());
        }
        return total;
    }

    private static String sanitize(String name) {
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
