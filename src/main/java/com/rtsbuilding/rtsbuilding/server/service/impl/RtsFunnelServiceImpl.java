package com.rtsbuilding.rtsbuilding.server.service.impl;

import com.rtsbuilding.rtsbuilding.common.build.BuilderMode;
import com.rtsbuilding.rtsbuilding.server.camera.RtsCameraManager;
import com.rtsbuilding.rtsbuilding.server.service.QuestService;
import com.rtsbuilding.rtsbuilding.server.service.RtsServiceConstants;
import com.rtsbuilding.rtsbuilding.server.service.RtsStorageTickService;
import com.rtsbuilding.rtsbuilding.server.service.ServiceRegistry;
import com.rtsbuilding.rtsbuilding.server.service.api.FunnelService;
import com.rtsbuilding.rtsbuilding.server.service.transfer.RtsTransferInserter;
import com.rtsbuilding.rtsbuilding.server.storage.cache.RtsAggregateStorage;
import com.rtsbuilding.rtsbuilding.server.storage.model.LinkedHandler;
import com.rtsbuilding.rtsbuilding.server.storage.resolver.RtsLinkedStorageResolver;
import com.rtsbuilding.rtsbuilding.server.storage.session.RtsStorageSession;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link FunnelService} 的默认实现——处理掉落物漏斗的启用/禁用、
 * 目标更新和每 Tick 的掉落物收集逻辑。
 *
 * <p>掉落物漏斗自动扫描目标位置附近的 {@link net.minecraft.world.entity.item.ItemEntity}，
 * 将掉落物吸入链接存储。当链接存储满时，多余物品会先存入玩家背包，
 * 再存入内部缓冲区。禁用漏斗时会清空缓冲区。
 */
public final class RtsFunnelServiceImpl implements FunnelService {

    private final ServiceRegistry registry = ServiceRegistry.getInstance();

    @Override
    public void enable(ServerPlayer player, RtsStorageSession session) {
        session.funnel.funnelEnabled = true;
        session.funnel.funnelTickCooldown = 0;
    }

    @Override
    public void disableAndFlush(ServerPlayer player, RtsStorageSession session) {
        session.funnel.funnelEnabled = false;
        session.funnel.funnelTarget = null;
        session.funnel.funnelTickCooldown = 0;
        if (session.funnel.funnelBuffer.isEmpty()) {
            return;
        }
        RtsLinkedStorageResolver.sanitizeSessionDimension(player, session);
        List<LinkedHandler> linked = RtsLinkedStorageResolver.resolveLinkedHandlers(player, session);
        List<IItemHandler> handlers = new ArrayList<>(linked.size());
        for (LinkedHandler h : linked) {
            handlers.add(h.handler());
        }
        RtsAggregateStorage aggregate = RtsStorageTickService.INSTANCE.getStorage(player);
        for (ItemStack buffered : session.funnel.funnelBuffer.values()) {
            if (buffered.isEmpty()) continue;
            ItemStack remain = aggregate != null && !aggregate.isEmpty()
                    ? aggregate.insert(buffered.copy(), false)
                    : RtsTransferInserter.storeToLinkedOnlyPreferExisting(handlers, buffered.copy());
            if (!remain.isEmpty()) {
                RtsTransferInserter.storeToLinkedWithFallback(handlers, player, remain);
            }
        }
        session.funnel.funnelBuffer.clear();
    }

    @Override
    public void updateTarget(ServerPlayer player, RtsStorageSession session, BlockPos target) {
        if (!session.funnel.funnelEnabled || target == null) return;
        session.funnel.funnelTarget = target.immutable();
    }

    @Override
    public void tick(ServerPlayer player, RtsStorageSession session) {
        if (!session.funnel.funnelEnabled || session.mode != BuilderMode.FUNNEL) return;
        if (session.funnel.funnelTickCooldown > 0) {
            session.funnel.funnelTickCooldown--;
            return;
        }
        session.funnel.funnelTickCooldown = RtsServiceConstants.FUNNEL_TICK_INTERVAL - 1;

        RtsLinkedStorageResolver.sanitizeSessionDimension(player, session);
        if (session.funnel.funnelTarget == null) return;
        if (!RtsLinkedStorageResolver.canAccessWorldTarget(player, session.funnel.funnelTarget)) return;
        if (!RtsCameraManager.isWithinActionRange(player, session.funnel.funnelTarget)) return;

        List<LinkedHandler> linked = RtsLinkedStorageResolver.resolveLinkedHandlers(player, session);
        List<IItemHandler> handlers = new ArrayList<>(linked.size());
        for (LinkedHandler lh : linked) {
            handlers.add(lh.handler());
        }

        boolean changed = flushBuffer(handlers, player, session);
        changed |= absorbDrops(player, session.funnel.funnelTarget, handlers, session);
        if (changed) {
            registry.page().markStorageViewDirty(player, session);
            QuestService.runQuestDetect(player, session, false);
        }
    }

    // ────────────────────────────────────────────────────────────────
    //  Internal helpers
    // ────────────────────────────────────────────────────────────────

    private boolean flushBuffer(List<IItemHandler> handlers, ServerPlayer player, RtsStorageSession session) {
        if (session.funnel.funnelBuffer.isEmpty()) return false;
        boolean changed = false;
        List<String> toRemove = new ArrayList<>();
        RtsAggregateStorage aggregate = RtsStorageTickService.INSTANCE.getStorage(player);
        for (var entry : session.funnel.funnelBuffer.entrySet()) {
            ItemStack buffered = entry.getValue();
            if (buffered.isEmpty()) {
                toRemove.add(entry.getKey());
                changed = true;
                continue;
            }
            ItemStack remain = aggregate != null && !aggregate.isEmpty()
                    ? aggregate.insert(buffered.copy(), false)
                    : RtsTransferInserter.storeToLinkedOnlyPreferExisting(handlers, buffered.copy());
            if (!remain.isEmpty()) {
                remain = RtsTransferInserter.moveToPlayerInventoryOnly(player, remain);
            }
            if (remain.isEmpty()) {
                toRemove.add(entry.getKey());
                changed = true;
            } else if (remain.getCount() != buffered.getCount()) {
                session.funnel.funnelBuffer.put(entry.getKey(), remain);
                changed = true;
            }
        }
        toRemove.forEach(session.funnel.funnelBuffer::remove);
        return changed;
    }

    private boolean absorbDrops(ServerPlayer player, BlockPos target, List<IItemHandler> handlers, RtsStorageSession session) {
        AABB box = new AABB(target).inflate(RtsServiceConstants.FUNNEL_RADIUS);
        List<ItemEntity> drops = player.serverLevel().getEntitiesOfClass(
                ItemEntity.class, box,
                e -> e != null && e.isAlive() && !e.getItem().isEmpty());

        int processedEntities = 0;
        int processedItems = 0;
        boolean changed = false;
        RtsAggregateStorage aggregate = RtsStorageTickService.INSTANCE.getStorage(player);

        for (ItemEntity drop : drops) {
            if (processedEntities >= RtsServiceConstants.FUNNEL_MAX_ENTITIES_PER_TICK
                    || processedItems >= RtsServiceConstants.FUNNEL_MAX_ITEMS_PER_TICK) {
                break;
            }
            processedEntities++;
            ItemStack worldStack = drop.getItem();
            if (worldStack.isEmpty()) continue;

            int remainingBudget = RtsServiceConstants.FUNNEL_MAX_ITEMS_PER_TICK - processedItems;
            int iterations = Math.min(worldStack.getCount(), remainingBudget);
            for (int i = 0; i < iterations; i++) {
                ItemStack one = worldStack.copy();
                one.setCount(1);
                ItemStack remain = aggregate != null && !aggregate.isEmpty()
                        ? aggregate.insert(one, false)
                        : RtsTransferInserter.storeToLinkedOnlyPreferExisting(handlers, one);
                if (!remain.isEmpty()) {
                    remain = RtsTransferInserter.moveToPlayerInventoryOnly(player, remain);
                }
                if (!remain.isEmpty()) {
                    remain = addToBuffer(session, remain);
                }
                if (!remain.isEmpty()) break;
                worldStack.shrink(1);
                processedItems++;
                changed = true;
                if (worldStack.isEmpty()) break;
            }
            if (worldStack.isEmpty()) {
                drop.discard();
            } else {
                drop.setItem(worldStack);
            }
        }
        return changed;
    }

    private ItemStack addToBuffer(RtsStorageSession session, ItemStack stack) {
        ItemStack remain = stack.copy();
        if (remain.isEmpty()) return ItemStack.EMPTY;

        ResourceLocation id = BuiltInRegistries.ITEM.getKey(remain.getItem());
        if (id == null) return remain;
        String key = id.toString();

        ItemStack existing = session.funnel.funnelBuffer.get(key);
        if (existing != null && !existing.isEmpty()
                && ItemStack.isSameItemSameComponents(existing, remain)) {
            int free = Math.max(0, existing.getMaxStackSize() - existing.getCount());
            int move = Math.min(free, remain.getCount());
            if (move > 0) {
                existing.grow(move);
                remain.shrink(move);
            }
        }

        while (!remain.isEmpty()) {
            existing = session.funnel.funnelBuffer.get(key);
            if (existing != null && !existing.isEmpty()
                    && ItemStack.isSameItemSameComponents(existing, remain)) {
                int free = Math.max(0, existing.getMaxStackSize() - existing.getCount());
                int move = Math.min(free, remain.getCount());
                if (move > 0) {
                    existing.grow(move);
                    remain.shrink(move);
                    if (remain.isEmpty()) break;
                }
            }
            if (session.funnel.funnelBuffer.size() >= RtsServiceConstants.FUNNEL_BUFFER_MAX_STACKS) break;
            int move = Math.min(remain.getCount(), remain.getMaxStackSize());
            ItemStack chunk = remain.copy();
            chunk.setCount(move);
            session.funnel.funnelBuffer.put(key, chunk);
            remain.shrink(move);
        }
        return remain;
    }
}
