package com.rtsbuilding.rtsbuilding.server.service;

import com.rtsbuilding.rtsbuilding.common.BuilderMode;
import com.rtsbuilding.rtsbuilding.network.storage.C2SRtsFunnelCollectPayload;
import com.rtsbuilding.rtsbuilding.server.camera.RtsCameraManager;
import com.rtsbuilding.rtsbuilding.server.service.transfer.RtsTransferInserter;
import com.rtsbuilding.rtsbuilding.server.storage.LinkedHandler;
import com.rtsbuilding.rtsbuilding.server.storage.RtsLinkedStorageResolver;
import com.rtsbuilding.rtsbuilding.server.storage.RtsStorageSession;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.List;

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
        List<LinkedHandler> linked = RtsLinkedStorageResolver.resolveLinkedHandlers(player, session);
        List<IItemHandler> handlers = new ArrayList<>(linked.size());
        for (LinkedHandler h : linked) {
            handlers.add(h.handler());
        }
        for (ItemStack buffered : session.funnel.funnelBuffer) {
            if (buffered.isEmpty()) continue;
            ItemStack remain = RtsTransferInserter.storeToLinkedOnlyPreferExisting(handlers, buffered);
            if (!remain.isEmpty()) {
                RtsTransferInserter.storeToLinkedWithFallback(handlers, player, remain);
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

        List<LinkedHandler> linked = RtsLinkedStorageResolver.resolveLinkedHandlers(player, session);
        List<IItemHandler> handlers = new ArrayList<>(linked.size());
        for (LinkedHandler lh : linked) {
            handlers.add(lh.handler());
        }

        boolean changed = flushBuffer(handlers, player, session);
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

        List<LinkedHandler> linked = RtsLinkedStorageResolver.resolveLinkedHandlers(player, session);
        List<IItemHandler> handlers = new ArrayList<>(linked.size());
        for (LinkedHandler lh : linked) {
            handlers.add(lh.handler());
        }

        var level = player.serverLevel();
        BlockPos funnelPos = session.funnel.funnelTarget;
        int processed = 0;
        boolean changed = false;

        for (int i = 0; i < payload.entityIds().size() && processed < RtsServiceConstants.FUNNEL_MAX_ENTITIES_PER_TICK; i++) {
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
            ItemStack toStore = worldStack.copy();
            toStore.setCount(actualCount);
            ItemStack remain = RtsTransferInserter.storeToLinkedOnlyPreferExisting(handlers, toStore);
            if (!remain.isEmpty()) {
                remain = RtsTransferInserter.moveToPlayerInventoryOnly(player, remain);
            }
            if (!remain.isEmpty()) {
                remain = addToBuffer(session, remain);
            }
            int consumed = actualCount - remain.getCount();
            if (consumed > 0) {
                worldStack.shrink(consumed);
                processed++;
                changed = true;
            }
            if (worldStack.isEmpty()) {
                drop.discard();
            } else {
                drop.setItem(worldStack);
            }
        }

        if (changed) {
            RtsSessionService.markStorageViewDirty(player, session);
        }
    }

    // ---- 内部方法 ----

    private static boolean flushBuffer(List<IItemHandler> handlers, ServerPlayer player, RtsStorageSession session) {
        if (session.funnel.funnelBuffer.isEmpty()) return false;
        boolean changed = false;
        for (int i = 0; i < session.funnel.funnelBuffer.size(); i++) {
            ItemStack buffered = session.funnel.funnelBuffer.get(i);
            if (buffered.isEmpty()) {
                session.funnel.funnelBuffer.remove(i);
                i--;
                changed = true;
                continue;
            }
            ItemStack remain = RtsTransferInserter.storeToLinkedOnlyPreferExisting(handlers, buffered);
            if (!remain.isEmpty()) {
                remain = RtsTransferInserter.moveToPlayerInventoryOnly(player, remain);
            }
            if (remain.isEmpty()) {
                session.funnel.funnelBuffer.remove(i);
                i--;
                changed = true;
            } else if (remain.getCount() != buffered.getCount()) {
                session.funnel.funnelBuffer.set(i, remain);
                changed = true;
            }
        }
        return changed;
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
