package com.rtsbuilding.rtsbuilding.server.storage.state;

import com.rtsbuilding.rtsbuilding.server.service.destruction.RtsDestroyBuffer;
import com.rtsbuilding.rtsbuilding.server.service.destruction.RtsDestructionBatch;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayDeque;
import java.util.Deque;

public class RtsDestructionState {

    public static final String TAG_ACTIVE_DESTROY_JOBS = "active_destroy_jobs";
    public static final String TAG_PENDING_DESTROY_JOBS = "pending_destroy_jobs";

    public final Deque<RtsDestructionBatch.DestructionJob> destroyJobs = new ArrayDeque<>();
    public final Deque<RtsDestructionBatch.DestructionJob> pendingDestroyJobs = new ArrayDeque<>();

    /** 当前 tick 的掉落物缓冲区，破坏循环中收集掉落物，循环结束后批量 flush。 */
    public RtsDestroyBuffer tickBuffer;

    public void toNbt(CompoundTag root) {
        ListTag activeList = new ListTag();
        for (RtsDestructionBatch.DestructionJob job : destroyJobs) {
            if (job != null) activeList.add(job.toNbt());
        }
        root.put(TAG_ACTIVE_DESTROY_JOBS, activeList);

        ListTag pendingList = new ListTag();
        for (RtsDestructionBatch.DestructionJob job : pendingDestroyJobs) {
            if (job != null) pendingList.add(job.toNbt());
        }
        root.put(TAG_PENDING_DESTROY_JOBS, pendingList);
    }

    public void fromNbt(CompoundTag root) {
        destroyJobs.clear();
        ListTag activeList = root.getList(TAG_ACTIVE_DESTROY_JOBS, Tag.TAG_COMPOUND);
        for (int i = 0; i < activeList.size(); i++) {
            destroyJobs.addLast(RtsDestructionBatch.DestructionJob.fromNbt(activeList.getCompound(i)));
        }

        pendingDestroyJobs.clear();
        ListTag pendingList = root.getList(TAG_PENDING_DESTROY_JOBS, Tag.TAG_COMPOUND);
        for (int i = 0; i < pendingList.size(); i++) {
            pendingDestroyJobs.addLast(RtsDestructionBatch.DestructionJob.fromNbt(pendingList.getCompound(i)));
        }
    }
}
