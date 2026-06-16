package com.rtsbuilding.rtsbuilding.client.screen.storage;

import com.rtsbuilding.rtsbuilding.client.controller.ClientRtsController;
import com.rtsbuilding.rtsbuilding.client.screen.panel.RtsWindowPanel;
import com.rtsbuilding.rtsbuilding.client.screen.standalone.BuilderScreen;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;

/**
 * Floating window that mimics the vanilla Minecraft inventory screen layout
 * while staying inside the RTS {@code BuilderScreen}.
 *
 * <p>Displays: 4 armour slots, offhand, 27-slot main inventory grid,
 * 9-slot hotbar, and optionally a 2×2 crafting grid plus the player entity
 * preview.  Click the hotbar row to select the active item; click a main
 * inventory slot to swap it with the currently selected hotbar slot.
 * Armour slots support left-click to equip / swap.
 */
public final class VanillaInventoryPanel extends RtsWindowPanel {

    // ---- layout constants (matching vanilla InventoryScreen) ---------------
    private static final int BG_WIDTH = 176;
    private static final int BG_HEIGHT = 166;
    private static final int SLOT_SIZE = 18;
    private static final int LEFT_OFF = 8;
    private static final ResourceLocation INVENTORY_BG = ResourceLocation.withDefaultNamespace(
            "textures/gui/container/inventory.png");

    // ---- colours -----------------------------------------------------------
    private static final int HOVER_TINT = 0x22FFFFFF;
    private static final int SELECTED_TINT = 0x33FFFF00;

    private ClientRtsController controller;

    public VanillaInventoryPanel() {
        super();
        this.windowX = 100;
        this.windowY = 160;
    }

    @Override
    public void init(BuilderScreen screen, ClientRtsController controller) {
        super.init(screen, controller);
        this.controller = controller;
        setTitle(Component.translatable("screen.rtsbuilding.vanilla_inventory"));
    }

    // ========================================================================
    //  RtsWindowPanel abstracts
    // ========================================================================

    @Override
    protected int getDefaultWidth() {
        return BG_WIDTH + 4;
    }

    @Override
    protected int getDefaultHeight() {
        return getTitleBarHeight() + BG_HEIGHT + 4;
    }

    @Override
    protected void computeDefaultPosition() {
        int sw = this.screen.width;
        int sh = this.screen.height;
        this.windowX = Math.max(10, (sw - getDefaultWidth()) / 2);
        this.windowY = Math.max(40, (sh - getDefaultHeight()) / 2);
    }

    // ========================================================================
    //  Slot mapping helpers
    // ========================================================================

    /**
     * Screen-slot index → inventory accessor.
     *
     * <pre>
     *  0   result        — skipped (no InventoryMenu in RTS)
     *  1-4 crafting 2×2 — skipped
     *  5-8 armour        → inventory.armor (3=helmet,2=chest,1=leggings,0=boots)
     *  9-35 main inv      → inventory.items[0-26]
     * 36-44 hotbar        → inventory.items[27-35]
     * 45   offhand        → inventory.offhand[0]
     * </pre>
     */
    private static final int[] EMPTY = new int[0];

    private ItemStack getItemForScreenSlot(Inventory inv, int screenSlot) {
        return switch (screenSlot) {
            case 5  -> inv.armor.get(3); // helmet
            case 6  -> inv.armor.get(2); // chestplate
            case 7  -> inv.armor.get(1); // leggings
            case 8  -> inv.armor.get(0); // boots
            case 45 -> inv.offhand.getFirst();
            default -> {
                if (screenSlot >= 9 && screenSlot < 36) {
                    yield inv.items.get(screenSlot - 9);  // main inv 0-26
                }
                if (screenSlot >= 36 && screenSlot < 45) {
                    yield inv.items.get(screenSlot - 9);  // hotbar 27-35
                }
                yield ItemStack.EMPTY;
            }
        };
    }

    private void setItemForScreenSlot(Inventory inv, int screenSlot, ItemStack stack) {
        switch (screenSlot) {
            case 5  -> inv.armor.set(3, stack); // helmet
            case 6  -> inv.armor.set(2, stack); // chestplate
            case 7  -> inv.armor.set(1, stack); // leggings
            case 8  -> inv.armor.set(0, stack); // boots
            case 45 -> inv.offhand.set(0, stack);
            default -> {
                if ((screenSlot >= 9 && screenSlot < 36) || (screenSlot >= 36 && screenSlot < 45)) {
                    inv.items.set(screenSlot - 9, stack);
                }
            }
        }
    }

    // ========================================================================
    //  Screen-slot → pixel coords (vanilla layout, relative to content top-left)
    // ========================================================================

    private int slotX(int screenSlot) {
        return switch (screenSlot) {
            case 0  -> 154; // result
            case 1  -> 98;
            case 2  -> 116;
            case 3  -> 98;
            case 4  -> 116;
            case 45 -> 77;
            default -> {
                if ((screenSlot >= 5 && screenSlot <= 8) || (screenSlot >= 9 && screenSlot < 45)) {
                    yield LEFT_OFF + ((screenSlot >= 9 && screenSlot < 45)
                            ? ((screenSlot - 9) % 9) * SLOT_SIZE : 0);
                }
                yield 0;
            }
        };
    }

    private int slotY(int screenSlot) {
        return switch (screenSlot) {
            case 0  -> 28;
            case 1  -> 18;
            case 2  -> 18;
            case 3  -> 36;
            case 4  -> 36;
            case 5  -> 8;   // helmet
            case 6  -> 26;  // chestplate
            case 7  -> 44;  // leggings
            case 8  -> 62;  // boots
            case 45 -> 62;  // offhand
            default -> {
                if (screenSlot >= 9 && screenSlot < 36) {
                    yield 84 + ((screenSlot - 9) / 9) * SLOT_SIZE;
                }
                if (screenSlot >= 36 && screenSlot < 45) {
                    yield 142;
                }
                yield 0;
            }
        };
    }

    // ========================================================================
    //  Rendering
    // ========================================================================

    @Override
    protected void renderContent(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        var inv = mc.player.getInventory();
        int selected = inv.selected;

        int bgX = contentX() + 2;
        int bgY = contentY() + 2;

        // vanilla inventory texture background
        g.blit(INVENTORY_BG, bgX, bgY, 0, 0, BG_WIDTH, BG_HEIGHT, 256, 256);

        // player model
        int playerX = bgX + 51;
        int playerY = bgY + 75;
        renderPlayerModel(g, mc, playerX, playerY, 30, mouseX, mouseY);

        int hoveredSlot = hitTestSlot(mouseX, mouseY, bgX, bgY);

        // draw all slots
        for (int slot = 5; slot <= 45; slot++) {
            int sx = bgX + slotX(slot);
            int sy = bgY + slotY(slot);
            drawSlot(g, slot, sx, sy, selected, slot == hoveredSlot);
        }

        // render carried item (picked up by mouse)
        ItemStack carried = mc.player.inventoryMenu.getCarried();
        if (!carried.isEmpty()) {
            g.renderItem(carried, mouseX - 8, mouseY - 8);
            g.renderItemDecorations(mc.font, carried, mouseX - 8, mouseY - 8);
        }

        // tooltip for hovered slot
        if (hoveredSlot >= 5 && hoveredSlot <= 45) {
            ItemStack hoveredStack = getItemForScreenSlot(inv, hoveredSlot);
            if (!hoveredStack.isEmpty()) {
                g.renderTooltip(mc.font, hoveredStack, mouseX, mouseY);
            }
        }
    }

    private void drawSlot(GuiGraphics g, int screenSlot,
            int x, int y, int selected, boolean hovered) {
        if (screenSlot == selected + 36) {
            g.fill(x + 1, y + 1, x + SLOT_SIZE - 1, y + SLOT_SIZE - 1, SELECTED_TINT);
        }
        if (hovered) {
            g.fill(x + 1, y + 1, x + SLOT_SIZE - 1, y + SLOT_SIZE - 1, HOVER_TINT);
        }

        ItemStack stack = getItemForScreenSlot(Minecraft.getInstance().player.getInventory(), screenSlot);
        if (!stack.isEmpty()) {
            this.renderLayer.renderSlotItem(g, Minecraft.getInstance().font,
                    stack, x, y, SLOT_SIZE, stack.getCount());
        } else if (screenSlot >= 5 && screenSlot <= 8) {
            drawArmourSlotHint(g, x, y, screenSlot);
        }
    }

    // ========================================================================
    //  Click handling — delegates to vanilla InventoryMenu.clickSlotId
    // ========================================================================

    @Override
    protected void handleContentClick(double mouseX, double mouseY, int button) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        int bgX = contentX() + 2;
        int bgY = contentY() + 2;
        int slot = hitTestSlot((int) mouseX, (int) mouseY, bgX, bgY);
        if (slot < 5 || slot > 45) return;

        var menu = mc.player.inventoryMenu;
        boolean hasShift = Screen.hasShiftDown();
        boolean hasNothingOnCursor = menu.getCarried().isEmpty();

        // hotbar left-click with empty cursor → just select slot
        if (slot >= 36 && slot < 45 && button == 0 && hasNothingOnCursor && !hasShift) {
            mc.player.getInventory().selected = slot - 36;
            return;
        }

        ClickType type = hasShift ? ClickType.QUICK_MOVE : ClickType.PICKUP;
        menu.clicked(slot, button, type, mc.player);
    }

    private void drawArmourSlotHint(GuiGraphics g, int x, int y, int screenSlot) {
        int color = 0x55FFFFFF;
        int cx = x + SLOT_SIZE / 2;
        int cy = y + SLOT_SIZE / 2;
        switch (screenSlot) {
            case 5 -> { // helmet outline
                g.fill(cx - 3, cy - 4, cx + 3, cy - 1, color);
                for (int i = -3; i <= 3; i++) g.fill(cx + i, cy, cx + i, cy + 4, color);
            }
            case 6 -> { // chestplate outline
                g.fill(cx - 3, cy - 4, cx + 3, cy + 4, color);
                g.fill(cx - 3, cy - 4, cx + 3, cy - 2, 0); // neck hole
            }
            case 7 -> g.fill(cx - 3, cy, cx + 3, cy + 4, color); // leggings
            case 8 -> g.fill(cx - 3, cy - 2, cx + 3, cy + 4, color); // boots
        }
    }

    @SuppressWarnings("deprecation")
    private void renderPlayerModel(GuiGraphics g, Minecraft mc, int x, int y, int size,
            int mouseX, int mouseY) {
        if (mc.player == null) return;
        RenderSystem.enableBlend();
        net.minecraft.client.gui.screens.inventory.InventoryScreen.renderEntityInInventoryFollowsMouse(
                g, x, y, x + size, y + size, size, 0.0625F, mouseX, mouseY, mc.player);
        RenderSystem.disableBlend();
    }

    // ========================================================================
    //  Hit testing
    // ========================================================================

    private int hitTestSlot(int mouseX, int mouseY, int bgX, int bgY) {
        for (int slot = 5; slot <= 45; slot++) {
            int sx = bgX + slotX(slot);
            int sy = bgY + slotY(slot);
            if (mouseX >= sx && mouseX < sx + SLOT_SIZE
                    && mouseY >= sy && mouseY < sy + SLOT_SIZE) {
                return slot;
            }
        }
        return -1;
    }
}
