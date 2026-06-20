package com.rtsbuilding.rtsbuilding.compat;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Marker interface for item handlers that support slot-agnostic insert/extract.
 * Implementations bypass the per-slot iteration loop in aggregate storage,
 * instead offering direct bulk operations.
 */
public interface SlotlessItemHandler extends IItemHandler {

    ItemStack insertItemSlotless(ItemStack stack, boolean simulate);

    default ItemStack extractItemSlotless(ItemStack toExtract, boolean simulate) {
        return ItemStack.EMPTY;
    }
}
