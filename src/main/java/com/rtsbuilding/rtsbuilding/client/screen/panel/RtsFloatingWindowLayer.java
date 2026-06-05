package com.rtsbuilding.rtsbuilding.client.screen.panel;

import java.util.List;

/**
 * Routes input for movable RTS windows in front-to-back order.
 *
 * <p>The layer owns only window stacking/input dispatch. It deliberately does
 * not know what a window does internally, which gameplay action it represents,
 * or how persistent UI state is saved. That keeps the current mainline screen
 * behavior intact while giving future windows a single place to join click,
 * drag, release, and scroll handling.
 */
public final class RtsFloatingWindowLayer {
    private final List<RtsWindowPanel> frontToBackWindows;

    public RtsFloatingWindowLayer(RtsWindowPanel... frontToBackWindows) {
        this.frontToBackWindows = List.of(frontToBackWindows);
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        for (RtsWindowPanel window : this.frontToBackWindows) {
            if (window.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
        }
        return false;
    }

    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        for (RtsWindowPanel window : this.frontToBackWindows) {
            if (window.mouseDragged(mouseX, mouseY, button, dragX, dragY)) {
                return true;
            }
        }
        return false;
    }

    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        boolean handled = false;
        for (RtsWindowPanel window : this.frontToBackWindows) {
            handled = window.mouseReleased(mouseX, mouseY, button) || handled;
        }
        return handled;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        for (RtsWindowPanel window : this.frontToBackWindows) {
            if (window.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) {
                return true;
            }
        }
        return false;
    }
}
