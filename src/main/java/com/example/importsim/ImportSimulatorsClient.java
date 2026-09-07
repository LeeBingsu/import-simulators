package com.example.importsim;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * Adds the "Import Simulators" button to the singleplayer world-select screen.
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
        });
    }
}
