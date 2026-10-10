package com.example.importsim;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Opened by the "Import Simulators" button. Lists every map in the catalog with a checkbox;
 * "Download (N)" imports only the ticked ones. The list can be searched by name and sorted by
 * name or by when the map was added.
 */
public class MapSelectScreen extends Screen {

    private static final Logger LOG = LoggerFactory.getLogger("import-simulators");

    private final Screen parent;
    private Config cfg;

    private static final Comparator<Catalog.MapEntry> BY_NAME =
            (a, b) -> a.name().compareToIgnoreCase(b.name());
    // "added" is an ISO-8601 instant, so comparing it as text orders it by time.
    private static final Comparator<Catalog.MapEntry> NEWEST =
            Comparator.comparing(Catalog.MapEntry::added).reversed().thenComparing(BY_NAME);

    /** Kept across openings of the screen, so the player's choice sticks for the session. */
    private static volatile boolean newestFirst = false;

    private volatile List<Catalog.MapEntry> maps;   // null while loading
    private volatile List<Catalog.MapEntry> shown = List.of();   // maps after search and sort
    private volatile Set<String> newMaps = Set.of();             // added since the player last looked
    private String query = "";
    private volatile String loadError;
    private final Set<String> selected = new LinkedHashSet<>();   // map names
    private volatile Set<String> alreadyImported = Set.of();      // map names already in saves/

    private int scroll;
    private final int rowHeight = 14;
    private int listTop;
    private int listBottom;

    private TextFieldWidget searchField;
    private ButtonWidget sortButton;
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
        this.listTop = 64;
        this.listBottom = this.height - 40;

        if (maps == null && loadError == null) {
            startLoad();
        } else {
            refreshAlreadyImported();
        }

        int searchW = 200;
        int sortW = 100;
        int sx = this.width / 2 - (searchW + 5 + sortW) / 2;
        searchField = new TextFieldWidget(this.textRenderer, sx, 22, searchW, 18, Text.literal("Search"));
        searchField.setPlaceholder(Text.literal("Search simulators…"));
        searchField.setText(query);
        searchField.setChangedListener(text -> {
            query = text;
            scroll = 0;
            applyFilter();
        });
        addDrawableChild(searchField);
        setInitialFocus(searchField);

        sortButton = ButtonWidget.builder(sortLabel(), b -> {
            newestFirst = !newestFirst;
            b.setMessage(sortLabel());
            scroll = 0;
            applyFilter();
        }).dimensions(sx + searchW + 5, 21, sortW, 20).build();
        addDrawableChild(sortButton);

        int bw = 74;
        int gap = 5;
        int totalW = bw * 5 + gap * 4;
        int x = this.width / 2 - totalW / 2;
        int y = this.height - 30;

        addDrawableChild(ButtonWidget.builder(Text.literal("All"), b -> {
            if (maps != null) {
                // Only what the search shows, so "All" after a search picks just the matches.
                shown.forEach(e -> selected.add(e.name()));
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
                List<Catalog.MapEntry> all = new ArrayList<>(new Catalog(cfg.catalog).maps());
                this.newMaps = new HashSet<>(NewSimulators.unseenMaps());
                // Rows first, so the list never shows loaded-but-empty for a frame.
                this.shown = filtered(all);
                this.maps = all;
                refreshAlreadyImported();
                // The player is looking at the list now, so the badge has done its job.
                NewSimulators.markSeen(all.stream().map(Catalog.MapEntry::name).toList());
            } catch (Exception e) {
                LOG.error("[import-simulators] Failed to load map list", e);
                this.loadError = e.getMessage() == null ? e.toString() : e.getMessage();
            }
        }, "import-simulators-list");
        t.setDaemon(true);
        t.start();
    }

    private Text sortLabel() {
        return Text.literal(newestFirst ? "Sort: Newest" : "Sort: Name");
    }

    /** Rebuilds the visible rows from the search text and the sort order. */
    private void applyFilter() {
        List<Catalog.MapEntry> all = maps;
        if (all != null) {
            shown = filtered(all);
        }
    }

    private List<Catalog.MapEntry> filtered(List<Catalog.MapEntry> all) {
        String q = query.trim().toLowerCase(Locale.ROOT);
        List<Catalog.MapEntry> out = new ArrayList<>();
        for (Catalog.MapEntry e : all) {
            if (q.isEmpty() || e.name().toLowerCase(Locale.ROOT).contains(q)) {
                out.add(e);
            }
        }
        out.sort(newestFirst ? NEWEST : BY_NAME);
        return out;
    }

    private void startDownload() {
        if (maps == null || selected.isEmpty() || ImportTask.isRunning()) {
            return;
        }
        List<Catalog.MapEntry> chosen = new ArrayList<>();
        for (Catalog.MapEntry e : maps) {
            if (selected.contains(e.name())) {
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
        List<Catalog.MapEntry> list = maps;
        if (list == null) {
            return;
        }
        Set<String> found = new HashSet<>();
        for (Catalog.MapEntry e : list) {
            if (ImportTask.isAlreadyImported(e.name())) {
                found.add(e.name());
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
            int newKits = NewSimulators.kitCount();
            kitsButton.setMessage(Text.literal(newKits > 0 ? "Kits (" + newKits + " new)" : "Import Kits"));
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
            List<Catalog.MapEntry> rows = shown;
            int idx = (int) ((mouseY - listTop + scroll) / rowHeight);
            if (idx >= 0 && idx < rows.size()) {
                String name = rows.get(idx).name();
                if (!selected.remove(name)) {
                    selected.add(name);
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
            int content = shown.size() * rowHeight;
            int view = listBottom - listTop;
            int max = Math.max(0, content - view);
            scroll = (int) Math.max(0, Math.min(max, scroll - verticalAmount * rowHeight * 2));
        }
        return true;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 8, 0xFFFFFFFF);

        if (!ImportTask.isRunning() && lastStatus != null) {
            ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(lastStatus),
                    this.width / 2, 43, 0xFFFFAA00);
            drawReason(ctx, lastFailureDetail, 53);
        }

        if (ImportTask.isRunning()) {
            ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(ImportTask.progress()),
                    this.width / 2, this.height / 2 - 24, 0xFFFFFFFF);

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
                    this.width / 2, by + barH + 6, 0xFFAAAAAA);
            drawReason(ctx, ImportTask.failureDetail(), by + barH + 18);
            return;
        }
        if (loadError != null) {
            ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Failed to load list: " + loadError),
                    this.width / 2, this.height / 2, 0xFFFF5555);
            return;
        }
        if (maps == null) {
            ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Loading map list…"),
                    this.width / 2, this.height / 2, 0xFFAAAAAA);
            return;
        }

        List<Catalog.MapEntry> rows = shown;
        if (rows.isEmpty()) {
            ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("No simulators match \"" + query + "\""),
                    this.width / 2, listTop + 10, 0xFFAAAAAA);
        }
        int x = listX();
        int w = listW();
        ctx.enableScissor(x, listTop, x + w, listBottom);
        int y = listTop - scroll;
        for (int i = 0; i < rows.size(); i++, y += rowHeight) {
            if (y + rowHeight < listTop || y > listBottom) {
                continue;
            }
            Catalog.MapEntry e = rows.get(i);
            boolean sel = selected.contains(e.name());
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
            int tagRight = x + w - 4;
            if (alreadyImported.contains(e.name())) {
                tagRight = drawTag(ctx, "re-download", tagRight, y + 3, 0xFFFFAA00);
            }
            if (newMaps.contains(e.name())) {
                tagRight = drawTag(ctx, "NEW", tagRight, y + 3, 0xFF55FF55);
            }
            nameW -= (x + w - 4) - tagRight;
            ctx.drawTextWithShadow(this.textRenderer,
                    Text.literal(this.textRenderer.trimToWidth(e.name(), nameW)), nameX, y + 3,
                    sel ? 0xFFFFFFFF : 0xFFBBBBBB);
        }
        ctx.disableScissor();

        ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal(selected.size() + " selected  —  " + rows.size() + " / " + maps.size() + " shown"),
                this.width / 2, listBottom + 4, 0xFFAAAAAA);
    }

    /** Draws a tag ending at {@code right}; returns where the next tag to its left should end. */
    private int drawTag(DrawContext ctx, String tag, int right, int y, int color) {
        int tagW = this.textRenderer.getWidth(tag);
        ctx.drawTextWithShadow(this.textRenderer, Text.literal(tag), right - tagW, y, color);
        return right - tagW - 6;
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
