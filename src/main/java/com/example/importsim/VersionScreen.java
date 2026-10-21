package com.example.importsim;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/**
 * The part of the list screen that depends on the Minecraft version: mouse clicks arrive as a
 * {@link Click} from 1.21.9 on, and as coordinates before that. carpet-1.21/ has the 1.21 copy.
 */
abstract class VersionScreen extends Screen {

    protected VersionScreen(Text title) {
        super(title);
    }

    /** @return true when the click was handled. */
    protected abstract boolean listClicked(double mouseX, double mouseY, int button);

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        return listClicked(click.x(), click.y(), click.button()) || super.mouseClicked(click, doubled);
    }
}
