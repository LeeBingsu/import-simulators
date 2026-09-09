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
 * Downloads a chosen set of Drive map folders into the saves directory on a background thread.
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

    private static final String QUOTA_DETAIL =
            "Google caps how often these files can be downloaded — it frees up in about a day";

    private static String describe(String what, Exception e) {
        String msg = e.getMessage() == null ? e.toString() : e.getMessage();
        return what + " — " + msg;
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

    /** True when the map already has a folder in saves, so importing it would re-download it. */
    public static boolean isAlreadyImported(String mapName) {
        return Files.isDirectory(savesDir().resolve(sanitize(mapName)));
    }

    private static Path savesDir() {
        return FabricLoader.getInstance().getGameDir().resolve("saves");
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
        failureDetail = "";
        totalBytes = 0;
        downloadedBytes.set(0);
        Thread t = new Thread(() -> run(client, returnScreen, maps, cfg), "import-simulators");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Syncs the Drive kits folder into the vexbot_kits folder in the game directory, creating it
     * when missing and downloading only the files the player does not already have.
     */
    public static void launchKits(MinecraftClient client, Screen returnScreen, Config cfg) {
        if (!RUNNING.compareAndSet(false, true)) {
            return;
        }
        progress = "Starting…";
        failureDetail = "";
        totalBytes = 0;
        downloadedBytes.set(0);
        Thread t = new Thread(() -> runKits(client, returnScreen, cfg), "import-simulators-kits");
        t.setDaemon(true);
        t.start();
    }

    private static void runKits(MinecraftClient client, Screen returnScreen, Config cfg) {
        Path kits = FabricLoader.getInstance().getGameDir().resolve(KITS_DIR);
        int added = 0;
        int failed = 0;
        int quotaFailed = 0;
        try {
            Files.createDirectories(kits);
            GDrive drive = new GDrive(cfg.googleApiKey);
            KitMirror mirror = new KitMirror(cfg.kitsMirror);

            progress = "Checking kits…";
            List<MissingFile> missing = new ArrayList<>();
            collectMissingKits(drive, cfg.kitsFolderId(), kits, missing);

            long size = 0;
            for (MissingFile f : missing) {
                size += Math.max(0, f.entry().size());
            }
            totalBytes = size;

            int n = missing.size();
            for (int i = 0; i < n; i++) {
                MissingFile f = missing.get(i);
                progress = String.format(Locale.ROOT, "Adding kit %d/%d: %s", i + 1, n, f.dest().getFileName());
                try {
                    // The mirror has no download cap; Drive is only for kits it does not carry yet.
                    if (!mirror.download(f.entry().name(), f.dest(), downloadedBytes::addAndGet)) {
                        drive.downloadFile(f.entry().id(), f.dest(), downloadedBytes::addAndGet);
                    }
                    added++;
                } catch (GDrive.QuotaExceededException e) {
                    failed++;
                    quotaFailed++;
                    failureDetail = QUOTA_DETAIL;
                    LOG.warn("[import-simulators] Skipping kit '{}': {}", f.entry().name(), e.getMessage());
                    deleteQuietly(f.dest());
                } catch (Exception e) {
                    failed++;
                    failureDetail = describe(f.entry().name(), e);
                    LOG.error("[import-simulators] Failed downloading kit '{}'", f.entry().name(), e);
                    deleteQuietly(f.dest());
                }
            }
        } catch (Exception e) {
            LOG.error("[import-simulators] Kit import aborted", e);
            failed++;
            failureDetail = describe("Could not read the kit list", e);
        }

        if (failed > 0) {
            copyToClipboard(client, cfg.kitsFolderUrl());
            LOG.warn("[import-simulators] Some kits did not download. Grab them by hand:");
            LOG.warn("[import-simulators]   Drive folder : {}  (copied to clipboard)", cfg.kitsFolderUrl());
            LOG.warn("[import-simulators]   Put them in  : {}", kits.toAbsolutePath());
            if (quotaFailed == failed) {
                // Google caps how often a popular public file can be downloaded; it frees up later,
                // and re-running only fetches what is still missing.
                progress = added + " added, " + failed + " hit Google's download limit — retry later";
            } else {
                progress = added + " kit(s) added, " + failed + " failed — Drive link copied";
            }
        } else {
            progress = added == 0 ? "Kits already up to date" : added + " kit(s) added";
        }
        LOG.info("[import-simulators] Kits done. added={} failed={}", added, failed);

        finish(client, returnScreen);
    }

    /** A Drive file the player is missing locally, paired with where it should land. */
    private record MissingFile(GDrive.Entry entry, Path dest) {
    }

    private static void collectMissingKits(GDrive drive, String folderId, Path dir, List<MissingFile> out)
            throws Exception {
        for (GDrive.Entry e : drive.listFolder(folderId)) {
            Path child = dir.resolve(sanitize(e.name()));
            if (e.isFolder()) {
                collectMissingKits(drive, e.id(), child, out);
            } else if (!alreadyHave(child, e.size())) {
                out.add(new MissingFile(e, child));
            }
        }
    }

    private static void run(MinecraftClient client, Screen returnScreen, List<GDrive.Entry> maps, Config cfg) {
        int imported = 0;
        int quotaBlocked = 0;
        List<String> failedMaps = new ArrayList<>();
        String folderUrl = cfg.folderUrl();
        Path saves = savesDir();
        try {
            GDrive drive = new GDrive(cfg.googleApiKey);
            MapRelease release = new MapRelease(cfg.mapsRelease);
            Files.createDirectories(saves);
            // Kept between runs: a map that failed part-way resumes instead of re-fetching
            // everything, which matters because Google caps how often a file can be downloaded.
            Path tmpRoot = saves.resolve(".import-simulators-tmp");
            Files.createDirectories(tmpRoot);

            progress = "Calculating download size…";
            long size = 0;
            for (GDrive.Entry mf : maps) {
                MapRelease.Asset asset = release.find(mf.name());
                if (asset != null) {
                    size += asset.size();
                    continue;
                }
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
                    MapRelease.Asset asset = release.find(mf.name());
                    Path world;
                    if (asset != null) {
                        // One request for the whole world, and no per-file download cap.
                        deleteRecursive(stage);
                        release.downloadInto(asset, stage, downloadedBytes::addAndGet);
                        world = unwrapSingleFolder(stage);
                    } else {
                        downloadFolderRecursive(drive, mf.id(), stage, safe, i + 1, n);
                        world = stage;
                    }

                    Path target;
                    if (cfg.overwriteExisting) {
                        target = saves.resolve(safe);
                        deleteRecursive(target);
                    } else {
                        target = uniqueDir(saves, safe);
                    }
                    Files.move(world, target);
                    if (!world.equals(stage)) {
                        deleteRecursive(stage);
                    }
                    imported++;
                    LOG.info("[import-simulators] Imported '{}' -> saves/{}", safe, target.getFileName());
                } catch (GDrive.QuotaExceededException e) {
                    failedMaps.add(safe);
                    quotaBlocked++;
                    failureDetail = QUOTA_DETAIL;
                    LOG.warn("[import-simulators] '{}' stopped at Google's download limit; "
                            + "what downloaded is kept and will resume next run", safe);
                } catch (Exception e) {
                    failedMaps.add(safe);
                    failureDetail = describe(safe, e);
                    LOG.error("[import-simulators] Failed importing '{}'", safe, e);
                }
            }
            if (failedMaps.isEmpty()) {
                deleteRecursive(tmpRoot);
            }
        } catch (Exception e) {
            LOG.error("[import-simulators] Import aborted", e);
            failureDetail = describe("Import aborted", e);
            if (failedMaps.isEmpty()) {
                failedMaps.add("(all)");
            }
        }

        final int fi = imported;
        final List<String> ff = List.copyOf(failedMaps);
        if (!ff.isEmpty()) {
            announceManual(client, folderUrl, saves, ff);
            progress = quotaBlocked == ff.size()
                    ? fi + " imported, " + ff.size() + " hit Google's download limit — retry later"
                    : fi + " imported, " + ff.size() + " failed — Drive link copied";
        } else {
            progress = fi + " map(s) imported";
        }
        LOG.info("[import-simulators] Done. imported={} failed={}", fi, ff.size());

        finish(client, returnScreen);
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

    private static void copyToClipboard(MinecraftClient client, String url) {
        try {
            client.keyboard.setClipboard(url);
        } catch (Throwable ignored) {
            // clipboard is best-effort
        }
    }

    /** Copy the Drive folder link to the clipboard and log what to grab by hand. */
    private static void announceManual(MinecraftClient client, String folderUrl, Path saves, List<String> failedMaps) {
        copyToClipboard(client, folderUrl);
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
            } else if (alreadyHave(child, e.size())) {
                downloadedBytes.addAndGet(e.size());   // carried over from an earlier run
            } else {
                progress = String.format(Locale.ROOT, "Importing %d/%d: %s — %s", idx, total, label, safe);
                try {
                    drive.downloadFile(e.id(), child, downloadedBytes::addAndGet);
                } catch (Exception ex) {
                    // Never leave a partial behind; it would look complete to the next run.
                    deleteQuietly(child);
                    throw ex;
                }
            }
        }
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

    /** Recursively sums declared file sizes under a Drive folder, without downloading anything. */
    private static long sumSizes(GDrive drive, String folderId) throws Exception {
        long total = 0;
        for (GDrive.Entry e : drive.listFolder(folderId)) {
            total += e.isFolder() ? sumSizes(drive, e.id()) : Math.max(0, e.size());
        }
        return total;
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
