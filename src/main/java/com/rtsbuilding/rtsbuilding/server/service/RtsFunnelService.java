package com.rtsbuilding.rtsbuilding.server.service;

import com.rtsbuilding.rtsbuilding.common.BuilderMode;
import com.rtsbuilding.rtsbuilding.network.storage.C2SRtsFunnelCollectPayload;
import com.rtsbuilding.rtsbuilding.server.camera.RtsCameraManager;
import com.rtsbuilding.rtsbuilding.server.service.transfer.RtsBatchInsertService;
import com.rtsbuilding.rtsbuilding.server.service.transfer.RtsTransferInserter;
import com.rtsbuilding.rtsbuilding.server.storage.RtsAggregateStorage;
import com.rtsbuilding.rtsbuilding.server.storage.RtsLinkedStorageResolver;
import com.rtsbuilding.rtsbuilding.server.storage.RtsStorageSession;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 掉落物漏斗服务——自动收集地面掉落物并存入链接存储。
 *
 * <p>职责范围：
 * <ul>
 *   <li>漏斗开关管理</li>
 *   <li>漏斗缓冲区管理</li>
 *   <li>Tick 处理：吸收掉落物 → 存入缓冲区 → 刷入链接存储</li>
 * </ul>
 */
public final class RtsFunnelService {

    private RtsFunnelService() {
    }

    /**
     * 启用漏斗。
     */
    public static void enable(ServerPlayer player, RtsStorageSession session) {
        session.funnel.funnelEnabled = true;
        session.funnel.funnelTickCooldown = 0;
    }

    /**
     * 禁用漏斗并清空缓冲区。
     */
    public static void disableAndFlush(ServerPlayer player, RtsStorageSession session) {
        session.funnel.funnelEnabled = false;
        session.funnel.funnelTarget = null;
        session.funnel.funnelTickCooldown = 0;
        if (session.funnel.funnelBuffer.isEmpty()) {
            return;
        }
        RtsLinkedStorageResolver.sanitizeSessionDimension(player, session);
        RtsAggregateStorage aggregate = RtsStorageTickService.INSTANCE.getStorage(player);
        if (aggregate != null && !aggregate.isEmpty()) {
            Map<String, ItemStack> bufferMap = collectBufferMap(session);
            if (!bufferMap.isEmpty()) {
                RtsBatchInsertService.batchInsertWithFallback(player, aggregate, bufferMap);
            }
        } else {
            for (ItemStack buffered : session.funnel.funnelBuffer) {
                if (buffered.isEmpty()) continue;
                RtsTransferInserter.moveToPlayerInventoryOnly(player, buffered);
            }
        }
        session.funnel.funnelBuffer.clear();
    }

    /**
     * 更新漏斗目标位置。
     */
    public static void updateTarget(ServerPlayer player, RtsStorageSession session, BlockPos target) {
        if (!session.funnel.funnelEnabled || target == null) return;
        session.funnel.funnelTarget = target.immutable();
    }

    /**
     * Tick 处理漏斗逻辑 — only flushes the buffer; item collection
     * is handled via {@link #processClientCollectPayload} triggered by
     * the client.
     */
    public static void tick(ServerPlayer player, RtsStorageSession session) {
        if (!session.funnel.funnelEnabled || session.mode != BuilderMode.FUNNEL) return;
        if (session.funnel.funnelTickCooldown > 0) {
            session.funnel.funnelTickCooldown--;
            return;
        }
        session.funnel.funnelTickCooldown = RtsServiceConstants.FUNNEL_TICK_INTERVAL - 1;

        RtsLinkedStorageResolver.sanitizeSessionDimension(player, session);
        if (session.funnel.funnelTarget == null) return;
        if (!RtsLinkedStorageResolver.canAccessWorldTarget(player, session.funnel.funnelTarget)) return;
        if (!RtsCameraManager.isWithinActionRadius(player, session.funnel.funnelTarget)) return;

        RtsAggregateStorage aggregate = RtsStorageTickService.INSTANCE.getStorage(player);
        boolean changed = flushBuffer(aggregate, player, session);
        if (changed) {
            RtsSessionService.markStorageViewDirty(player, session);
        }
    }

    /**
     * Processes a client-calculated funnel collect payload.
     *
     * <p>The client pre-scans {@code ItemEntity}s within the funnel radius
     * and sends their IDs, item IDs and counts.  The server validates each
     * entity (alive, in-radius, item matches, count matches) before absorbing.
     */
    public static void processClientCollectPayload(ServerPlayer player, C2SRtsFunnelCollectPayload payload) {
        if (player == null || payload == null || payload.isEmpty()) return;
        RtsStorageSession session = RtsSessionService.getIfPresent(player);
        if (session == null) return;
        if (!session.funnel.funnelEnabled || session.mode != BuilderMode.FUNNEL) return;
        if (session.funnel.funnelTarget == null) return;

        RtsLinkedStorageResolver.sanitizeSessionDimension(player, session);
        if (!RtsLinkedStorageResolver.canAccessWorldTarget(player, session.funnel.funnelTarget)) return;
        if (!RtsCameraManager.isWithinActionRadius(player, session.funnel.funnelTarget)) return;

        RtsAggregateStorage aggregate = RtsStorageTickService.INSTANCE.getStorage(player);

        var level = player.serverLevel();
        BlockPos funnelPos = session.funnel.funnelTarget;
        int processed = 0;
        boolean changed = false;

        Map<String, ItemStack> collectMap = new HashMap<>();
        Map<Integer, ItemStack> entitySnapshots = new HashMap<>();

        for (int i = 0; i < payload.entityIds().size()
                && processed < RtsServiceConstants.FUNNEL_MAX_ENTITIES_PER_TICK; i++) {
            int entityId = payload.entityIds().get(i);
            String itemId = payload.itemIds().get(i);
            int reportedCount = payload.counts().get(i);
            if (reportedCount <= 0) continue;

            Entity entity = level.getEntity(entityId);
            if (!(entity instanceof ItemEntity drop) || !drop.isAlive()) continue;

            double distSq = drop.position().distanceToSqr(funnelPos.getCenter());
            if (distSq > RtsServiceConstants.FUNNEL_RADIUS * RtsServiceConstants.FUNNEL_RADIUS + 1.0) continue;

            ItemStack worldStack = drop.getItem();
            if (worldStack.isEmpty()) continue;

            ResourceLocation rl = BuiltInRegistries.ITEM.getKey(worldStack.getItem());
            if (rl == null || !rl.toString().equals(itemId)) continue;

            int actualCount = Math.min(worldStack.getCount(), reportedCount);
            ItemStack toStore = worldStack.copyWithCount(actualCount);

            if (aggregate != null && !aggregate.isEmpty()) {
                collectMap.merge(itemId, toStore, (existing, incoming) -> {
                    existing.grow(incoming.getCount());
                    return existing;
                });
                entitySnapshots.put(entityId, toStore.copy());
            } else {
                ItemStack remain = RtsTransferInserter.moveToPlayerInventoryOnly(player, toStore);
                if (!remain.isEmpty()) {
                    addToBuffer(session, remain);
                }
                worldStack.shrink(actualCount - remain.getCount());
                if (worldStack.isEmpty()) {
                    drop.discard();
                } else {
                    drop.setItem(worldStack);
                }
            }
            processed++;
            changed = true;
        }

        if (!collectMap.isEmpty()) {
            Map<String, ItemStack> overflow = aggregate.batchInsert(collectMap, false);
            for (var entry : overflow.entrySet()) {
                ItemStack remain = RtsTransferInserter.moveToPlayerInventoryOnly(player, entry.getValue());
                if (!remain.isEmpty()) {
                    addToBuffer(session, remain);
                }
            }
            // 从世界实体中扣除已消费的物品
            for (var snapshotEntry : entitySnapshots.entrySet()) {
                int entityId = snapshotEntry.getKey();
                Entity entity = level.getEntity(entityId);
                if (!(entity instanceof ItemEntity drop) || !drop.isAlive()) continue;
                ItemStack worldStack = drop.getItem();
                if (worldStack.isEmpty()) continue;
                String itemId = worldStack.getItem().toString();
                ItemStack snap = snapshotEntry.getValue();
                long consumed = snap.getCount();
                // 从overflow中减掉未消费的部分
                ItemStack leftover = overflow.get(itemId);
                if (leftover != null) {
                    consumed -= leftover.getCount();
                }
                if (consumed > 0) {
                    worldStack.shrink((int) consumed);
                }
                if (worldStack.isEmpty()) {
                    drop.discard();
                } else {
                    drop.setItem(worldStack);
                }
            }
        }

        if (changed) {
            RtsSessionService.markStorageViewDirty(player, session);
        }
    }

    // ---- 内部方法 ----

    private static boolean flushBuffer(RtsAggregateStorage aggregate, ServerPlayer player, RtsStorageSession session) {
        if (session.funnel.funnelBuffer.isEmpty()) return false;

        Map<String, ItemStack> bufferMap = collectBufferMap(session);
        if (bufferMap.isEmpty()) return false;

        if (aggregate != null && !aggregate.isEmpty()) {
            RtsBatchInsertService.batchInsertWithFallback(player, aggregate, bufferMap);
        } else {
            for (var entry : bufferMap.entrySet()) {
                RtsTransferInserter.moveToPlayerInventoryOnly(player, entry.getValue());
            }
        }
        session.funnel.funnelBuffer.clear();
        return true;
    }

    /** 将漏斗缓冲区中的物品堆按 itemId 合并到一个 map 中。 */
    private static Map<String, ItemStack> collectBufferMap(RtsStorageSession session) {
        Map<String, ItemStack> bufferMap = new HashMap<>();
        for (ItemStack buffered : session.funnel.funnelBuffer) {
            if (buffered.isEmpty()) continue;
            String itemId = buffered.getItem().toString();
            bufferMap.merge(itemId, buffered.copy(), (existing, incoming) -> {
                existing.grow(incoming.getCount());
                return existing;
            });
        }
        return bufferMap;
    }

    private static ItemStack addToBuffer(RtsStorageSession session, ItemStack stack) {
        ItemStack remain = stack.copy();
        if (remain.isEmpty()) return ItemStack.EMPTY;

        for (ItemStack buffered : session.funnel.funnelBuffer) {
            if (remain.isEmpty()) break;
            if (buffered.isEmpty() || !ItemStack.isSameItemSameComponents(buffered, remain)) continue;
            int free = Math.max(0, buffered.getMaxStackSize() - buffered.getCount());
            if (free <= 0) continue;
            int move = Math.min(free, remain.getCount());
            buffered.grow(move);
            remain.shrink(move);
        }

        while (!remain.isEmpty() && session.funnel.funnelBuffer.size() < RtsServiceConstants.FUNNEL_BUFFER_MAX_STACKS) {
            int move = Math.min(remain.getCount(), remain.getMaxStackSize());
            ItemStack chunk = remain.copy();
            chunk.setCount(move);
            session.funnel.funnelBuffer.add(chunk);
            remain.shrink(move);
        }
        return remain;
    }
}
