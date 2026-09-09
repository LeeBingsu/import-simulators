package com.example.importsim;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Opened by the "Import Simulators" button. Lists every map folder in the Drive folder with a
 * checkbox; "Download (N)" imports only the ticked ones.
 */
public class MapSelectScreen extends Screen {

    private static final Logger LOG = LoggerFactory.getLogger("import-simulators");

    private final Screen parent;
    private Config cfg;

    private volatile List<GDrive.Entry> maps;   // null while loading
    private volatile String loadError;
    private final Set<String> selected = new LinkedHashSet<>();   // folder ids
    private volatile Set<String> alreadyImported = Set.of();      // folder ids already in saves/

    private int scroll;
    private final int rowHeight = 14;
    private int listTop;
    private int listBottom;

    private ButtonWidget downloadButton;
    private ButtonWidget kitsButton;
    private boolean wasRunning;
    private String lastStatus;          // result of the import that just finished
    private String lastFailureDetail;   // why it failed, when it did

    public MapSelectScreen(Screen parent) {
        super(Text.literal("Import Simulators"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.cfg = Config.load();
        this.listTop = 52;
        this.listBottom = this.height - 40;

        if (maps == null && loadError == null) {
            startLoad();
        } else {
            refreshAlreadyImported();
        }

        int bw = 74;
        int gap = 5;
        int totalW = bw * 5 + gap * 4;
        int x = this.width / 2 - totalW / 2;
        int y = this.height - 30;

        addDrawableChild(ButtonWidget.builder(Text.literal("All"), b -> {
            if (maps != null) {
                maps.forEach(e -> selected.add(e.id()));
                refreshButtons();
            }
        }).dimensions(x, y, bw, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("None"), b -> {
            selected.clear();
            refreshButtons();
        }).dimensions(x + (bw + gap), y, bw, 20).build());

        downloadButton = ButtonWidget.builder(Text.literal("Download"), b -> startDownload())
                .dimensions(x + 2 * (bw + gap), y, bw, 20).build();
        addDrawableChild(downloadButton);

        kitsButton = ButtonWidget.builder(Text.literal("Import Kits"), b -> startKitsImport())
                .dimensions(x + 3 * (bw + gap), y, bw, 20).build();
        addDrawableChild(kitsButton);

        addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), b -> this.client.setScreen(parent))
                .dimensions(x + 4 * (bw + gap), y, bw, 20).build());

        refreshButtons();
    }

    private void startLoad() {
        Thread t = new Thread(() -> {
            try {
                List<GDrive.Entry> all = new GDrive(cfg.googleApiKey).listFolder(cfg.folderId());
                List<GDrive.Entry> folders = new ArrayList<>();
                for (GDrive.Entry e : all) {
                    if (e.isFolder()) {
                        folders.add(e);
                    }
                }
                folders.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
                this.maps = folders;
                refreshAlreadyImported();
            } catch (Exception e) {
                LOG.error("[import-simulators] Failed to load map list", e);
                this.loadError = e.getMessage() == null ? e.toString() : e.getMessage();
            }
        }, "import-simulators-list");
        t.setDaemon(true);
        t.start();
    }

    private void startDownload() {
        if (maps == null || selected.isEmpty() || ImportTask.isRunning()) {
            return;
        }
        List<GDrive.Entry> chosen = new ArrayList<>();
        for (GDrive.Entry e : maps) {
            if (selected.contains(e.id())) {
                chosen.add(e);
            }
        }
        ImportTask.launch(this.client, parent, chosen, cfg);
    }

    private void startKitsImport() {
        if (ImportTask.isRunning()) {
            return;
        }
        // Come back here afterwards so the player can carry on picking maps.
        ImportTask.launchKits(this.client, this, cfg);
    }

    /** Re-checks which maps already sit in saves/, so their rows can be marked as re-downloads. */
    private void refreshAlreadyImported() {
        List<GDrive.Entry> list = maps;
        if (list == null) {
            return;
        }
        Set<String> found = new HashSet<>();
        for (GDrive.Entry e : list) {
            if (ImportTask.isAlreadyImported(e.name())) {
                found.add(e.id());
            }
        }
        alreadyImported = found;
    }

    private void refreshButtons() {
        if (downloadButton == null) {
            return;
        }
        downloadButton.setMessage(Text.literal("Download (" + selected.size() + ")"));
        downloadButton.active = !selected.isEmpty() && maps != null && !ImportTask.isRunning();
        if (kitsButton != null) {
            kitsButton.active = !ImportTask.isRunning();
        }
    }

    /** Draws the failure reason, wrapped to the screen, so the player sees the actual cause. */
    private void drawReason(DrawContext ctx, String reason, int y) {
        if (reason == null || reason.isEmpty()) {
            return;
        }
        int max = this.width - 20;
        for (OrderedText line : this.textRenderer.wrapLines(Text.literal(reason), max)) {
            ctx.drawCenteredTextWithShadow(this.textRenderer, line, this.width / 2, y, 0xFFFF5555);
            y += 10;
        }
    }

    private int listX() {
        return this.width / 2 - 160;
    }

    private int listW() {
        return 320;
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        double mouseX = click.x();
        double mouseY = click.y();
        if (click.button() == 0 && maps != null && !ImportTask.isRunning()
                && mouseX >= listX() && mouseX <= listX() + listW()
                && mouseY >= listTop && mouseY < listBottom) {
            int idx = (int) ((mouseY - listTop + scroll) / rowHeight);
            if (idx >= 0 && idx < maps.size()) {
                String id = maps.get(idx).id();
                if (!selected.remove(id)) {
                    selected.add(id);
                }
                refreshButtons();
                return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (maps != null) {
            int content = maps.size() * rowHeight;
            int view = listBottom - listTop;
            int max = Math.max(0, content - view);
            scroll = (int) Math.max(0, Math.min(max, scroll - verticalAmount * rowHeight * 2));
        }
        return true;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 15, 0xFFFFFF);

        if (!ImportTask.isRunning() && lastStatus != null) {
            ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(lastStatus),
                    this.width / 2, 27, 0xFFFFAA00);
            drawReason(ctx, lastFailureDetail, 38);
        }

        if (ImportTask.isRunning()) {
            ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(ImportTask.progress()),
                    this.width / 2, this.height / 2 - 24, 0xFFFFFF);

            long total = ImportTask.totalBytes();
            long done = ImportTask.downloadedBytes();
            int barW = 240;
            int barH = 14;
            int bx = this.width / 2 - barW / 2;
            int by = this.height / 2 - 4;
            int fillW = total > 0 ? (int) Math.round(barW * Math.min(1.0, done / (double) total)) : 0;

            ctx.fill(bx - 1, by - 1, bx + barW + 1, by + barH + 1, 0xFF000000);
            ctx.fill(bx, by, bx + barW, by + barH, 0xFF404040);
            if (fillW > 0) {
                ctx.fill(bx, by, bx + fillW, by + barH, 0xFF44AA44);
            }

            String sizeText = total > 0
                    ? ImportTask.humanSize(done) + " / " + ImportTask.humanSize(total)
                            + "  (" + Math.min(100, done * 100L / total) + "%)"
                    : ImportTask.humanSize(done) + " downloaded";
            ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(sizeText),
                    this.width / 2, by + barH + 6, 0xAAAAAA);
            drawReason(ctx, ImportTask.failureDetail(), by + barH + 18);
            return;
        }
        if (loadError != null) {
            ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Failed to load list: " + loadError),
                    this.width / 2, this.height / 2, 0xFF5555);
            return;
        }
        if (maps == null) {
            ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Loading map list…"),
                    this.width / 2, this.height / 2, 0xAAAAAA);
            return;
        }

        int x = listX();
        int w = listW();
        ctx.enableScissor(x, listTop, x + w, listBottom);
        int y = listTop - scroll;
        for (int i = 0; i < maps.size(); i++, y += rowHeight) {
            if (y + rowHeight < listTop || y > listBottom) {
                continue;
            }
            GDrive.Entry e = maps.get(i);
            boolean sel = selected.contains(e.id());
            boolean hover = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY < y + rowHeight;
            if (hover) {
                ctx.fill(x, y, x + w, y + rowHeight, 0x30FFFFFF);
            }
            int bx = x + 2;
            int by = y + 2;
            int bs = 10;
            ctx.fill(bx, by, bx + bs, by + bs, 0xFF888888);
            ctx.fill(bx + 1, by + 1, bx + bs - 1, by + bs - 1, 0xFF202020);
            if (sel) {
                ctx.fill(bx + 2, by + 2, bx + bs - 2, by + bs - 2, 0xFF44DD44);
            }

            int nameX = x + 18;
            int nameW = w - 20;
            if (alreadyImported.contains(e.id())) {
                String tag = "re-download";
                int tagW = this.textRenderer.getWidth(tag);
                ctx.drawTextWithShadow(this.textRenderer, Text.literal(tag), x + w - tagW - 4, y + 3, 0xFFFFAA00);
                nameW -= tagW + 8;
            }
            ctx.drawTextWithShadow(this.textRenderer,
                    Text.literal(this.textRenderer.trimToWidth(e.name(), nameW)), nameX, y + 3,
                    sel ? 0xFFFFFFFF : 0xFFBBBBBB);
        }
        ctx.disableScissor();

        ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal(selected.size() + " / " + maps.size() + " selected  —  scroll to see more"),
                this.width / 2, listBottom + 4, 0xAAAAAA);
    }

    @Override
    public void tick() {
        boolean running = ImportTask.isRunning();
        if (wasRunning && !running) {
            refreshAlreadyImported();
            lastStatus = ImportTask.progress();
            lastFailureDetail = ImportTask.failureDetail();
        }
        wasRunning = running;
        refreshButtons();
    }

    @Override
    public void close() {
        this.client.setScreen(parent);
    }
}
