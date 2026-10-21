package com.example.importsim.carpet;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/** The 1.21 copy of the root project's VersionScreen: clicks arrive as plain coordinates. */
abstract class VersionScreen extends Screen {

    protected VersionScreen(Text title) {
        super(title);
    }

    /** @return true when the click was handled. */
    protected abstract boolean listClicked(double mouseX, double mouseY, int button);

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return listClicked(mouseX, mouseY, button) || super.mouseClicked(mouseX, mouseY, button);
    }
}
