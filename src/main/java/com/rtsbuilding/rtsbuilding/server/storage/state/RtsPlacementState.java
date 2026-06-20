package com.rtsbuilding.rtsbuilding.server.storage.state;

import com.rtsbuilding.rtsbuilding.server.service.placement.RtsPlaceBuffer;
import com.rtsbuilding.rtsbuilding.server.service.placement.RtsPlacementBatch;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.Deque;

public class RtsPlacementState {

    public static final String TAG_PENDING_PLACEMENT_JOBS = "pending_placement_jobs";
    public static final String TAG_ACTIVE_PLACEMENT_JOBS = "active_placement_jobs";

    public final Deque<RtsPlacementBatch.PlaceBatchJob> placeBatchJobs = new ArrayDeque<>();
    public final Deque<RtsPlacementBatch.PlaceBatchJob> pendingJobs = new ArrayDeque<>();
    public final Deque<PlacedRecoveryJob> recoveryJobs = new ArrayDeque<>();

    /** 当前 tick 的预提取缓冲区，非空时放置方法优先从中取物品而非走存储。 */
    public RtsPlaceBuffer tickBuffer;

    public record PlacedRecoveryJob(BlockPos targetPos, Deque<ItemStack> stacks) {}

    public void toNbt(ServerPlayer player, CompoundTag root) {
        ListTag pendingList = new ListTag();
        for (RtsPlacementBatch.PlaceBatchJob job : pendingJobs) {
            if (job != null) pendingList.add(job.toNbt(player.registryAccess()));
        }
        root.put(TAG_PENDING_PLACEMENT_JOBS, pendingList);

        ListTag activeList = new ListTag();
        for (RtsPlacementBatch.PlaceBatchJob job : placeBatchJobs) {
            if (job != null) activeList.add(job.toNbt(player.registryAccess()));
        }
        root.put(TAG_ACTIVE_PLACEMENT_JOBS, activeList);
    }

    public void fromNbt(ServerPlayer player, CompoundTag root) {
        pendingJobs.clear();
        ListTag pendingList = root.getList(TAG_PENDING_PLACEMENT_JOBS, Tag.TAG_COMPOUND);
        for (int i = 0; i < pendingList.size(); i++) {
            RtsPlacementBatch.PlaceBatchJob job = RtsPlacementBatch.PlaceBatchJob.fromNbt(
                    pendingList.getCompound(i), player.registryAccess());
            pendingJobs.addLast(job);
        }

        placeBatchJobs.clear();
        ListTag activeList = root.getList(TAG_ACTIVE_PLACEMENT_JOBS, Tag.TAG_COMPOUND);
        for (int i = 0; i < activeList.size(); i++) {
            RtsPlacementBatch.PlaceBatchJob job = RtsPlacementBatch.PlaceBatchJob.fromNbt(
                    activeList.getCompound(i), player.registryAccess());
            placeBatchJobs.addLast(job);
        }
    }
}
