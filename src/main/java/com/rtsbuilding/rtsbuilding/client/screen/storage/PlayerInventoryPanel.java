package com.rtsbuilding.rtsbuilding.client.screen.storage;

import com.rtsbuilding.rtsbuilding.client.controller.ClientRtsController;
import com.rtsbuilding.rtsbuilding.client.screen.panel.RtsWindowPanel;
import com.rtsbuilding.rtsbuilding.client.screen.standalone.BuilderScreen;
import com.rtsbuilding.rtsbuilding.client.screen.standalone.BuilderScreenConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * Floating window showing the player's own inventory (36 slots: 9 hotbar +
 * 27 main inventory) while in RTS camera mode.
 *
 * <p>Click a hotbar slot (0-8) to select it as the active main-hand item.
 * Click a main-inventory slot (9-35) to swap it with the currently
 * selected hotbar slot.  No server communication is needed — all slot
 * mutations and the {@code selected} index are client-authoritative.
 */
public final class PlayerInventoryPanel extends RtsWindowPanel {

    private static final int COLS = 9;
    private static final int ROWS = 4;
    private static final int SLOT_SIZE = BuilderScreenConstants.SLOT;  // 22
    private static final int PADDING = 4;
    private static final int GAP = 2;
    private static final int HOTBAR_DIVIDER = 4; // extra space between hotbar row and main inv

    // ---- colours ----
    private static final int SLOT_BG = 0xAA111111;
    private static final int HOVER_TINT = 0x22FFFFFF;
    private static final int SELECTED_TINT = 0x3326C56D;
    private static final int BORDER_LIGHT = 0xFF4A4A4A;
    private static final int BORDER_DARK = 0xFF1B1B1B;

    private ClientRtsController controller;

    public PlayerInventoryPanel() {
        super();
        this.windowX = 100;
        this.windowY = 200;
    }

    @Override
    public void init(BuilderScreen screen, ClientRtsController controller) {
        super.init(screen, controller);
        this.controller = controller;
        setTitle(Component.translatable("screen.rtsbuilding.player_inventory"));
    }

    // ========================================================================
    //  RtsWindowPanel abstract methods
    // ========================================================================

    @Override
    protected int getDefaultWidth() {
        return PADDING * 2 + COLS * SLOT_SIZE + (COLS - 1) * GAP;
    }

    @Override
    protected int getDefaultHeight() {
        return getTitleBarHeight() + PADDING + ROWS * SLOT_SIZE + (ROWS - 1) * GAP + HOTBAR_DIVIDER + PADDING;
    }

    @Override
    protected void computeDefaultPosition() {
        int sw = this.screen.width;
        int sh = this.screen.height;
        this.windowX = Math.max(10, (sw - getDefaultWidth()) / 2);
        this.windowY = Math.max(40, sh - getDefaultHeight() - 80);
    }

    // ========================================================================
    //  Rendering
    // ========================================================================

    @Override
    protected void renderContent(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        Inventory inv = mc.player.getInventory();
        int selected = inv.selected;

        // determine hovered slot
        int hoveredSlot = hitTestSlot(mouseX, mouseY);

        for (int slot = 0; slot < 36; slot++) {
            int col = slot % COLS;
            int row = slot / COLS;
            int x = contentX() + PADDING + col * (SLOT_SIZE + GAP);
            int yOff = row > 0 ? HOTBAR_DIVIDER : 0;
            int y = contentY() + PADDING + row * (SLOT_SIZE + GAP) + yOff;

            // slot background
            g.fill(x, y, x + SLOT_SIZE, y + SLOT_SIZE, SLOT_BG);

            // border
            g.hLine(x, x + SLOT_SIZE, y, BORDER_LIGHT);
            g.vLine(x, y, y + SLOT_SIZE, BORDER_LIGHT);
            g.hLine(x, x + SLOT_SIZE, y + SLOT_SIZE, BORDER_DARK);
            g.vLine(x + SLOT_SIZE, y, y + SLOT_SIZE, BORDER_DARK);

            // selection highlight
            if (slot == selected) {
                g.fill(x + 1, y + 1, x + SLOT_SIZE - 1, y + SLOT_SIZE - 1, SELECTED_TINT);
            }

            // hover highlight (overrides selection tint)
            if (slot == hoveredSlot) {
                g.fill(x + 1, y + 1, x + SLOT_SIZE - 1, y + SLOT_SIZE - 1, HOVER_TINT);
            }

            // item + count
            ItemStack stack = inv.getItem(slot);
            if (!stack.isEmpty()) {
                this.renderLayer.renderSlotItem(g, mc.font,
                        stack, x, y, SLOT_SIZE, stack.getCount());
            }
        }

        // tooltip for hovered slot
        if (hoveredSlot >= 0 && hoveredSlot < 36) {
            ItemStack hoveredStack = inv.getItem(hoveredSlot);
            if (!hoveredStack.isEmpty()) {
                g.renderTooltip(mc.font, hoveredStack, mouseX, mouseY);
            }
        }
    }

    // ========================================================================
    //  Click handling
    // ========================================================================

    @Override
    protected void handleContentClick(double mouseX, double mouseY, int button) {
        if (Minecraft.getInstance().player == null) return;

        int slot = hitTestSlot((int) mouseX, (int) mouseY);
        if (slot < 0 || slot >= 36) return;

        Inventory inv = Minecraft.getInstance().player.getInventory();

        if (button == 0) { // left click
            if (slot < 9) {
                // select hotbar slot
                inv.selected = slot;
            } else {
                // swap main-inventory slot with currently selected hotbar slot
                int selected = inv.selected;
                ItemStack hotbarItem = inv.getItem(selected);
                ItemStack clickedItem = inv.getItem(slot);
                inv.setItem(selected, clickedItem);
                inv.setItem(slot, hotbarItem);
            }
        }
    }

    // ========================================================================
    //  Slot hit-testing
    // ========================================================================

    private int hitTestSlot(int mouseX, int mouseY) {
        int relX = mouseX - contentX() - PADDING;
        int relY = mouseY - contentY() - PADDING;

        // check each row for the divider offset
        for (int row = 0; row < ROWS; row++) {
            int yOff = row > 0 ? HOTBAR_DIVIDER : 0;
            int rowStartY = row * (SLOT_SIZE + GAP) + yOff;
            int rowEndY = rowStartY + SLOT_SIZE;
            if (relY < rowStartY) return -1;
            if (relY >= rowStartY && relY < rowEndY) {
                int col = relX / (SLOT_SIZE + GAP);
                int colOff = relX % (SLOT_SIZE + GAP);
                if (col >= 0 && col < COLS && colOff < SLOT_SIZE) {
                    return row * COLS + col;
                }
                return -1;
            }
        }
        return -1;
    }
}
