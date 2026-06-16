package com.rtsbuilding.rtsbuilding.compat;

import net.minecraft.world.item.ItemStack;

/**
 * Marker interface for handlers that support slot-agnostic item extraction
 * and insertion — a single method call instead of slot-by-slot iteration.
 *
 * <p>Implemented at runtime via Mixin for SophisticatedCore's
 * {@code InventoryHandler}.  Checked with {@code instanceof} in
 * {@link com.rtsbuilding.rtsbuilding.server.storage.RtsAggregateStorage}
 * to route to the fast path.
 */
public interface SlotlessItemHandler {

    /**
     * Slot-agnostic extraction: extracts up to {@code stack.getCount()} items
     * matching the given stack (including NBT components).
     *
     * @return the extracted stack, or {@link ItemStack#EMPTY}
     */
    ItemStack extractItemSlotless(ItemStack stack, boolean simulate);

    /**
     * Slot-agnostic insertion: inserts the given stack into any suitable slot.
     *
     * @return the remainder (items that could not be inserted)
     */
    ItemStack insertItemSlotless(ItemStack stack, boolean simulate);
}
