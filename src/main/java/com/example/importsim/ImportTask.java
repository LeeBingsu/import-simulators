package com.example.importsim;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * Runs the whole import on a background daemon thread. All UI touches are marshalled
 * back onto the client thread with {@link MinecraftClient#execute}.
 */
public final class ImportTask {

    private static final Logger LOG = LoggerFactory.getLogger("import-simulators");
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static volatile String STATUS = null;
    /** Sticky label for the button after a run finishes with failures; survives screen rebuilds. */
    private static volatile String resultLabel = null;

    private ImportTask() {
    }

    public static boolean isRunning() {
        return RUNNING.get();
    }

    public static String status() {
        return STATUS == null ? "Import Simulators" : STATUS;
    }

    public static String resultLabel() {
        return resultLabel;
    }

    public static void launch(MinecraftClient client, Screen screen, ButtonWidget button) {
        if (!RUNNING.compareAndSet(false, true)) {
            return;
        }
        resultLabel = null;
        button.active = false;
        setStatus(button, "Importing… starting");
        Thread t = new Thread(() -> run(client, screen, button), "import-simulators");
        t.setDaemon(true);
        t.start();
    }

    private static void run(MinecraftClient client, Screen screen, ButtonWidget button) {
        int imported = 0;
        List<String> failedMaps = new ArrayList<>();
        Config cfg = Config.load();
        String folderUrl = cfg.folderUrl();
        Path saves = FabricLoader.getInstance().getGameDir().resolve("saves");
        try {
            String folderId = cfg.folderId();
            if (folderId == null || folderId.isBlank()) {
                throw new IllegalStateException("No Drive folder configured in config/import-simulators.json");
            }

            GDrive drive = new GDrive(cfg.googleApiKey);
            Files.createDirectories(saves);
            Path tmpRoot = saves.resolve(".import-simulators-tmp");
            deleteRecursive(tmpRoot);
            Files.createDirectories(tmpRoot);

            setStatus(button, "Listing Drive folder…");
            List<GDrive.Entry> top = drive.listFolder(folderId);
            List<GDrive.Entry> mapFolders = top.stream().filter(GDrive.Entry::isFolder).toList();
            if (mapFolders.isEmpty()) {
                throw new IllegalStateException("No map folders found in the Drive folder");
            }

            int n = mapFolders.size();
            for (int i = 0; i < n; i++) {
                GDrive.Entry mf = mapFolders.get(i);
                String safe = sanitize(mf.name());
                setStatus(button, String.format(Locale.ROOT, "Importing %d/%d: %s", i + 1, n, safe));
                Path stage = tmpRoot.resolve(safe);
                try {
                    downloadFolderRecursive(drive, mf.id(), stage, button, safe);

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

            final int fi = imported;
            final List<String> ff = List.copyOf(failedMaps);
            LOG.info("[import-simulators] Done. imported={} failed={}", fi, ff.size());
            client.execute(() -> {
                RUNNING.set(false);
                STATUS = null;
                if (!ff.isEmpty()) {
                    announceManual(client, button, folderUrl, saves, ff);
                }
                if (screen == client.currentScreen) {
                    // Rebuilds the world list from disk and re-adds our button (which will pick
                    // up resultLabel if the run had failures).
                    screen.resize(client, screen.width, screen.height);
                }
            });
        } catch (Exception e) {
            LOG.error("[import-simulators] Import aborted", e);
            client.execute(() -> {
                RUNNING.set(false);
                STATUS = null;
                button.active = true;
                announceManual(client, button, folderUrl, saves, failedMaps);
            });
        }
    }

    /**
     * Download failed (wholly or partly). Copy the Drive folder link to the clipboard and tell
     * the player to grab the maps by hand.
     */
    private static void announceManual(MinecraftClient client, ButtonWidget button,
                                       String folderUrl, Path saves, List<String> failedMaps) {
        try {
            client.keyboard.setClipboard(folderUrl);
        } catch (Throwable ignored) {
            // clipboard is best-effort
        }
        resultLabel = failedMaps.isEmpty()
                ? "Import failed — Drive link copied, download by hand"
                : failedMaps.size() + " map(s) failed — Drive link copied, download by hand";
        button.active = true;
        button.setMessage(Text.literal(resultLabel));

        LOG.warn("[import-simulators] Some maps did not import. Download them by hand:");
        LOG.warn("[import-simulators]   Drive folder : {}  (copied to clipboard)", folderUrl);
        LOG.warn("[import-simulators]   Put them in  : {}", saves.toAbsolutePath());
        if (!failedMaps.isEmpty()) {
            LOG.warn("[import-simulators]   Missing maps : {}", String.join(", ", failedMaps));
        }
    }

    private static void downloadFolderRecursive(GDrive drive, String folderId, Path dir,
                                                ButtonWidget button, String label) throws Exception {
        Files.createDirectories(dir);
        for (GDrive.Entry e : drive.listFolder(folderId)) {
            String safe = sanitize(e.name());
            Path child = dir.resolve(safe);
            if (e.isFolder()) {
                downloadFolderRecursive(drive, e.id(), child, button, label);
            } else {
                setStatus(button, "Importing " + label + " — " + safe);
                drive.downloadFile(e.id(), child);
            }
        }
    }

    private static void setStatus(ButtonWidget button, String s) {
        STATUS = s;
        MinecraftClient.getInstance().execute(() -> button.setMessage(Text.literal(s)));
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
