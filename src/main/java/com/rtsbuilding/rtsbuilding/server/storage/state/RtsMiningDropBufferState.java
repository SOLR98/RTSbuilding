package com.rtsbuilding.rtsbuilding.server.storage.state;

import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 自动存入的有界中间缓存。
 *
 * <p>它接住真实掉落并保留完整 ItemStack 组件；不负责解析储存或发包。缓存故意不写入普通
 * Session NBT，退出服务器前必须由服务层回退到背包或世界，避免复制物品。</p>
 */
public final class RtsMiningDropBufferState {
    public static final int MAX_BUFFERED_ITEMS = RtsMiningDropBufferPolicy.MAX_BUFFERED_ITEMS;
    public static final int MAX_STACKS = RtsMiningDropBufferPolicy.MAX_STACKS;

    public final Deque<ItemStack> stacks = new ArrayDeque<>();
    public int bufferedItems;
    public long firstQueuedGameTime = -1L;
    public long lastProgressGameTime = -1L;
    public boolean fullNoticeSent;

    public int remainingCapacity() {
        return RtsMiningDropBufferPolicy.remainingCapacity(bufferedItems);
    }

    public boolean isFull() {
        return RtsMiningDropBufferPolicy.isFull(bufferedItems, stacks.size());
    }

    public boolean isEmpty() {
        return stacks.isEmpty();
    }

    /**
     * 按完整 ItemStack 组件合并入队，并把逻辑数量拆成原版允许的正常堆叠。
     * 这里只管理中间缓冲，不访问绑定存储，也不会接管超过总容量的世界掉落。
     */
    public int enqueueMerged(ItemStack prototype, int requestedCount) {
        if (prototype == null || prototype.isEmpty() || requestedCount <= 0 || isFull()) {
            return 0;
        }
        int remaining = Math.min(requestedCount, remainingCapacity());
        int requested = remaining;
        int maxStackSize = Math.max(1, prototype.getMaxStackSize());

        for (ItemStack existing : stacks) {
            if (remaining <= 0) break;
            if (!ItemStack.isSameItemSameComponents(existing, prototype)) continue;
            int free = Math.max(0, Math.min(maxStackSize, existing.getMaxStackSize()) - existing.getCount());
            if (free <= 0) continue;
            int moved = Math.min(free, remaining);
            existing.grow(moved);
            remaining -= moved;
        }

        while (remaining > 0 && stacks.size() < MAX_STACKS) {
            int chunkSize = Math.min(remaining, maxStackSize);
            stacks.addLast(prototype.copyWithCount(chunkSize));
            remaining -= chunkSize;
        }

        int accepted = requested - remaining;
        bufferedItems += accepted;
        return accepted;
    }

    public void markQueued(long gameTime) {
        if (firstQueuedGameTime < 0L) firstQueuedGameTime = gameTime;
        if (lastProgressGameTime < 0L) lastProgressGameTime = gameTime;
    }

    public void markStorageProgress(long gameTime) {
        lastProgressGameTime = gameTime;
    }

    public boolean shouldNotifyFull(long gameTime) {
        return isFull() && RtsMiningDropBufferPolicy.shouldNotifyFull(lastProgressGameTime, gameTime);
    }

    public boolean shouldFallback(long gameTime) {
        return RtsMiningDropBufferPolicy.shouldFallback(lastProgressGameTime, gameTime);
    }

    public void clearTimingWhenEmpty() {
        if (stacks.isEmpty()) {
            bufferedItems = 0;
            firstQueuedGameTime = -1L;
            lastProgressGameTime = -1L;
            fullNoticeSent = false;
        }
    }
}
