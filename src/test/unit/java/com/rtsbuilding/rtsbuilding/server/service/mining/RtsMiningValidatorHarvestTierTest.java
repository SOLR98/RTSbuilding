package com.rtsbuilding.rtsbuilding.server.service.mining;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RtsMiningValidatorHarvestTierTest {
    @Test
    void reportsOnlyWhenAValidToolIsStoppedByThePluginTier() {
        BlockState diamondTierBlock = mock(BlockState.class);
        ItemStack validTool = mock(ItemStack.class);
        when(diamondTierBlock.requiresCorrectToolForDrops()).thenReturn(true);
        when(diamondTierBlock.is(BlockTags.NEEDS_DIAMOND_TOOL)).thenReturn(true);
        when(validTool.isEmpty()).thenReturn(false);
        when(validTool.isCorrectToolForDrops(diamondTierBlock)).thenReturn(true);

        assertTrue(RtsMiningValidator.isBlockedByRangeMiningHarvestTier(
                diamondTierBlock, validTool, false, RangeMiningHarvestTier.IRON.maxRequiredLevel()));
        assertFalse(RtsMiningValidator.isBlockedByRangeMiningHarvestTier(
                diamondTierBlock, validTool, false, RangeMiningHarvestTier.DIAMOND.maxRequiredLevel()));
        assertFalse(RtsMiningValidator.isBlockedByRangeMiningHarvestTier(
                diamondTierBlock, validTool, true, RangeMiningHarvestTier.WOOD.maxRequiredLevel()));
    }

    @Test
    void wrongToolIsNotMisreportedAsAPluginTierProblem() {
        BlockState diamondTierBlock = mock(BlockState.class);
        ItemStack wrongTool = mock(ItemStack.class);
        when(diamondTierBlock.requiresCorrectToolForDrops()).thenReturn(true);
        when(diamondTierBlock.is(BlockTags.NEEDS_DIAMOND_TOOL)).thenReturn(true);
        when(wrongTool.isEmpty()).thenReturn(false);
        when(wrongTool.isCorrectToolForDrops(diamondTierBlock)).thenReturn(false);

        assertFalse(RtsMiningValidator.isBlockedByRangeMiningHarvestTier(
                diamondTierBlock, wrongTool, false, RangeMiningHarvestTier.IRON.maxRequiredLevel()));
    }
}
