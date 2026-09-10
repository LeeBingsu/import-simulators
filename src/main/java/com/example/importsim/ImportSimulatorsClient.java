package com.example.importsim;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * Adds the "Import Simulators" button to the singleplayer world-select screen, with a badge
 * beside it when simulators have been added since the player last opened the list.
 * The button opens {@link MapSelectScreen}, where the player picks which maps to download.
 * No mixins: the button is added through the Fabric Screen API.
 */
public class ImportSimulatorsClient implements ClientModInitializer {

    public static final String MOD_ID = "import-simulators";

    @Override
    public void onInitializeClient() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof SelectWorldScreen)) {
                return;
            }
            ButtonWidget button = ButtonWidget.builder(
                    Text.literal("Import Simulators"),
                    btn -> client.setScreen(new MapSelectScreen(screen))
            ).dimensions(8, 8, 130, 20).build();

            Screens.getButtons(screen).add(button);
            NewSimulators.refresh();

            ScreenEvents.afterRender(screen).register((s, ctx, mouseX, mouseY, delta) -> {
                int count = NewSimulators.count();
                if (count > 0) {
                    drawBadge(ctx, client.textRenderer, button.getX() + button.getWidth() + 4,
                            button.getY() + 3, count);
                }
            });
        });
    }

    /** A small count beside the button, so a new simulator is noticeable without opening the list. */
    private static void drawBadge(DrawContext ctx, TextRenderer text, int x, int y, int count) {
        String label = count + " new";
        int width = text.getWidth(label) + 6;
        int height = 13;
        ctx.fill(x, y, x + width, y + height, 0xFF000000);
        ctx.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0xFFCC3333);
        ctx.drawTextWithShadow(text, Text.literal(label), x + 3, y + 3, 0xFFFFFFFF);
    }
}
