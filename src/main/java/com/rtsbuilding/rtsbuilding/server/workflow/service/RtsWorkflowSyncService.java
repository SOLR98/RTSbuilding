package com.rtsbuilding.rtsbuilding.server.workflow.service;

import com.rtsbuilding.rtsbuilding.network.builder.S2CRtsWorkflowProgressBatchPayload;
import com.rtsbuilding.rtsbuilding.network.builder.S2CRtsWorkflowProgressPayload;
import com.rtsbuilding.rtsbuilding.server.workflow.core.RtsWorkflowEntry;
import com.rtsbuilding.rtsbuilding.server.workflow.model.RtsWorkflowStatus;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles network synchronisation of workflow state from server to client.
 *
 * <p>This service encapsulates all {@link S2CRtsWorkflowProgressPayload}
 * creation and dispatch logic so that the engine does not need to deal
 * with wire-format details directly.</p>
 *
 * <p>Uses the positional index for payloads (as the client expects), but
 * the engine always identifies entries by their immutable entry ID.</p>
 */
public final class RtsWorkflowSyncService {

    private static final int MAX_WORKFLOWS = 8;

    /**
     * Sends all occupied workflow entries to the client as individual payloads.
     *
     * @param player the server-side player to notify
     * @param slots  the player's slot manager
     */
    public void notifyPlayer(ServerPlayer player, RtsWorkflowSlotManager slots) {
        if (player == null || slots == null) return;

        int totalCount = slots.occupiedCount();
        byte totalCountByte = (byte) Math.min(totalCount, 255);

        if (totalCount == 0) {
            PacketDistributor.sendToPlayer(player, S2CRtsWorkflowProgressPayload.idle());
            return;
        }

        // Collect all entries and send as a single batch packet
        List<S2CRtsWorkflowProgressPayload> entries = new ArrayList<>(totalCount);
        int entryCount = Math.min(slots.size(), MAX_WORKFLOWS);
        for (int i = 0; i < entryCount; i++) {
            RtsWorkflowEntry entry = slots.getEntry(i);
            if (entry == null || !entry.isOccupied()) continue;

            RtsWorkflowStatus status = entry.snapshot();
            entries.add(new S2CRtsWorkflowProgressPayload(
                    (byte) i,
                    totalCountByte,
                    status.type() != null ? (byte) status.type().ordinal() : (byte) -1,
                    (byte) status.priority().rank(),
                    status.totalBlocks(),
                    status.completedBlocks(),
                    status.failedBlocks(),
                    status.missingItems(),
                    status.detailMessage(),
                    status.suspended() ? (byte) 1 : (byte) 0,
                    status.paused() ? (byte) 1 : (byte) 0,
                    entry.id()));
        }
        PacketDistributor.sendToPlayer(player, new S2CRtsWorkflowProgressBatchPayload(entries));
    }

    /**
     * Sends a single-entry final snapshot to the client and then notifies
     * with the updated state.  Called when a workflow completes.
     */
    public void notifyCompletion(ServerPlayer player, RtsWorkflowSlotManager slots,
                                  RtsWorkflowEntry entry, int removedAtIndex) {
        if (player == null || entry == null) return;

        // Send final snapshot for this entry before removing it
        RtsWorkflowStatus status = entry.snapshot();
        int remainingCount = slots.occupiedCount() - 1;
        PacketDistributor.sendToPlayer(player, new S2CRtsWorkflowProgressPayload(
                (byte) removedAtIndex,
                (byte) remainingCount,
                status.type() != null ? (byte) status.type().ordinal() : (byte) -1,
                (byte) status.priority().rank(),
                status.totalBlocks(),
                status.completedBlocks(),
                status.failedBlocks(),
                status.missingItems(),
                status.detailMessage(),
                (byte) 0,
                (byte) 0,
                entry.id()));

        // Notify with updated state if any entries remain
        notifyPlayer(player, slots);
    }

    /**
     * Sends an idle (no active workflows) payload to the client.
     */
    public void sendIdle(ServerPlayer player) {
        if (player != null) {
            PacketDistributor.sendToPlayer(player, S2CRtsWorkflowProgressPayload.idle());
        }
    }
}
