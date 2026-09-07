package com.example.importsim;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * Registers an "Import Simulators" button on the singleplayer world-select screen.
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
                    btn -> ImportTask.launch(client, screen, btn)
            ).dimensions(8, 8, 200, 20).build();

            if (ImportTask.isRunning()) {
                // An import is in progress (e.g. the screen was just resized).
                button.active = false;
                button.setMessage(Text.literal(ImportTask.status()));
            } else if (ImportTask.resultLabel() != null) {
                // Last run finished with failures — keep the "download by hand" notice visible.
                button.setMessage(Text.literal(ImportTask.resultLabel()));
            }

            Screens.getButtons(screen).add(button);
        });
    }
}
