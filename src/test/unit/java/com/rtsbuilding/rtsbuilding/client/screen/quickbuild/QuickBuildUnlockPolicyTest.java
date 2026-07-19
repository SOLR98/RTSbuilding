package com.rtsbuilding.rtsbuilding.client.screen.quickbuild;

import com.rtsbuilding.rtsbuilding.client.screen.ultimine.AreaMineShape;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuickBuildUnlockPolicyTest {
    @Test
    void survivalDisabledKeepsEveryDestroyShapeAvailable() {
        assertTrue(QuickBuildUnlockPolicy.canUseDestroyShape(false, false, false, false, AreaMineShape.CHAIN));
        assertTrue(QuickBuildUnlockPolicy.canUseDestroyShape(false, false, false, false, AreaMineShape.BOX));
    }

    @Test
    void chainPluginOnlyUnlocksChainMining() {
        assertTrue(QuickBuildUnlockPolicy.canUseDestroyShape(true, true, false, false, AreaMineShape.CHAIN));
        assertFalse(QuickBuildUnlockPolicy.canUseDestroyShape(true, true, false, false, AreaMineShape.BLOCK));
        assertEquals(AreaMineShape.CHAIN,
                QuickBuildUnlockPolicy.firstAvailableDestroyShape(true, true, false, false));
    }

    @Test
    void areaAndHarvestPluginsUnlockNonChainShapesAndReplaceStaleChainDefault() {
        assertFalse(QuickBuildUnlockPolicy.canUseDestroyShape(true, false, true, true, AreaMineShape.CHAIN));
        assertTrue(QuickBuildUnlockPolicy.canUseDestroyShape(true, false, true, true, AreaMineShape.BLOCK));
        assertTrue(QuickBuildUnlockPolicy.canUseDestroyShape(true, false, true, true, AreaMineShape.BOX));
        assertEquals(AreaMineShape.BLOCK,
                QuickBuildUnlockPolicy.firstAvailableDestroyShape(true, false, true, true));
    }

    @Test
    void areaPluginWithoutHarvestTierKeepsNonChainShapesLocked() {
        assertFalse(QuickBuildUnlockPolicy.canUseDestroyShape(
                true, false, true, false, AreaMineShape.BLOCK));
        assertFalse(QuickBuildUnlockPolicy.canUseAnyDestroyShape(true, false, true, false));
        assertNull(QuickBuildUnlockPolicy.firstAvailableDestroyShape(true, false, true, false));
    }

    @Test
    void noDestroyPluginLeavesDestroyModeUnavailable() {
        assertFalse(QuickBuildUnlockPolicy.canUseAnyDestroyShape(true, false, false, false));
        assertNull(QuickBuildUnlockPolicy.firstAvailableDestroyShape(true, false, false, false));
    }
}
