package com.rtsbuilding.rtsbuilding.server.storage.state;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RtsMiningDropBufferStateTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        if (LoadingModList.get() == null) {
            LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        }
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void capacityAppliesBackpressureAtHardLimit() {
        RtsMiningDropBufferState state = new RtsMiningDropBufferState();
        state.bufferedItems = RtsMiningDropBufferState.MAX_BUFFERED_ITEMS - 1;
        assertEquals(1, state.remainingCapacity());
        assertFalse(state.isFull());
        state.bufferedItems++;
        assertTrue(state.isFull());
        assertEquals(0, state.remainingCapacity());
    }

    @Test
    void emptyResetClearsTimeoutAndNotice() {
        RtsMiningDropBufferState state = new RtsMiningDropBufferState();
        state.firstQueuedGameTime = 123L;
        state.fullNoticeSent = true;
        state.clearTimingWhenEmpty();
        assertEquals(-1L, state.firstQueuedGameTime);
        assertFalse(state.fullNoticeSent);
    }

    @Test
    void stackCountAlsoAppliesBackpressureToHeavyNbtItems() {
        assertEquals(4096, RtsMiningDropBufferState.MAX_STACKS);
        assertFalse(RtsMiningDropBufferPolicy.isFull(
                0, RtsMiningDropBufferState.MAX_STACKS - 1));
        assertTrue(RtsMiningDropBufferPolicy.isFull(
                0, RtsMiningDropBufferState.MAX_STACKS));
    }

    @Test
    void fragmentedCobblestoneMergesIntoNormalStacks() {
        RtsMiningDropBufferState state = new RtsMiningDropBufferState();

        for (int i = 0; i < 4096; i++) {
            assertEquals(1, state.enqueueMerged(new ItemStack(Items.COBBLESTONE), 1));
        }

        assertEquals(4096, state.bufferedItems);
        assertEquals(64, state.stacks.size());
        assertTrue(state.isFull());
    }

    @Test
    void noticeAndFallbackMeasureTimeSinceStorageProgress() {
        RtsMiningDropBufferState state = new RtsMiningDropBufferState();
        state.bufferedItems = RtsMiningDropBufferState.MAX_BUFFERED_ITEMS;
        state.markQueued(100L);

        assertFalse(state.shouldNotifyFull(119L));
        assertTrue(state.shouldNotifyFull(120L));
        assertFalse(state.shouldFallback(159L));
        assertTrue(state.shouldFallback(160L));

        state.markStorageProgress(150L);
        assertFalse(state.shouldNotifyFull(169L));
        assertFalse(state.shouldFallback(209L));
        assertTrue(state.shouldFallback(210L));
    }
}
