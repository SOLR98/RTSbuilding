package com.rtsbuilding.rtsbuilding.server.service;

import com.rtsbuilding.rtsbuilding.common.BuilderMode;
import com.rtsbuilding.rtsbuilding.compat.remote.RtsRemoteMenuCompat;
import com.rtsbuilding.rtsbuilding.network.storage.S2CRtsStorageDirtyPayload;
import com.rtsbuilding.rtsbuilding.progression.RtsFeature;
import com.rtsbuilding.rtsbuilding.server.data.RtsStorageSessionCodec;
import com.rtsbuilding.rtsbuilding.server.data.RtsStorageSessionStore;
import com.rtsbuilding.rtsbuilding.server.history.ServerHistoryManager;
import com.rtsbuilding.rtsbuilding.server.pipeline.core.TickablePipelineRegistry;
import com.rtsbuilding.rtsbuilding.server.progression.RtsProgressionManager;
import com.rtsbuilding.rtsbuilding.server.service.mining.RtsMiningStateMachine;
import com.rtsbuilding.rtsbuilding.server.service.page.RtsPageCore;
import com.rtsbuilding.rtsbuilding.server.service.placement.RtsPlacementBatch;
import com.rtsbuilding.rtsbuilding.server.storage.RtsLinkedStorageResolver;
import com.rtsbuilding.rtsbuilding.server.storage.RtsStoragePageBuilder;
import com.rtsbuilding.rtsbuilding.server.storage.RtsStorageSession;
import com.rtsbuilding.rtsbuilding.server.workflow.core.RtsWorkflowEngine;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RTS 会话管理器——会话生命周期与玩家状态的管理核心。
 *
 * <p>职责范围：
 * <ul>
 *   <li>玩家 RTS 会话（{@link RtsStorageSession}）的创建、获取、持久化</li>
 *   <li>全局会话映射（SESSIONS）的维护</li>
 *   <li>生命周期钩子的统一调度（启用/停用 RTS、登出、Tick）</li>
 * </ul>
 */
public final class RtsSessionService {

    private static final Map<UUID, RtsStorageSession> SESSIONS = new ConcurrentHashMap<>();

    private RtsSessionService() {
    }

    // ======================================================================
    // 会话获取
    // ======================================================================

    /**
     * 获取或创建玩家的 RTS 会话。
     *
     * <p>使用 {@link ConcurrentHashMap#computeIfAbsent} 保证原子性，
     * 避免并发场景下 check-then-act 导致的会话静默覆盖。
     */
    public static RtsStorageSession getOrCreate(ServerPlayer player) {
        return SESSIONS.computeIfAbsent(player.getUUID(), uuid -> {
            RtsStorageSession session = new RtsStorageSession();
            loadFromPersistentStorage(player, session);
            return session;
        });
    }

    /**
     * 获取玩家会话但不创建（可能返回 null）。
     */
    public static RtsStorageSession getIfPresent(ServerPlayer player) {
        return SESSIONS.get(player.getUUID());
    }

    /**
     * 获取所有活跃会话。
     */
    public static Map<UUID, RtsStorageSession> allSessions() {
        return Collections.unmodifiableMap(SESSIONS);
    }

    // ======================================================================
    // 持久化
    // ======================================================================

    private static void loadFromPersistentStorage(ServerPlayer player, RtsStorageSession session) {
        var root = RtsStorageSessionStore.loadSession(player);
        boolean loadedFromWorldStore = !root.isEmpty();
        if (!loadedFromWorldStore) {
            root = player.getPersistentData().getCompound(RtsStorageSessionCodec.ROOT_KEY);
        }
        if (root.isEmpty()) {
            return;
        }
        RtsStorageSessionCodec.load(player, session, root);
        if (!loadedFromWorldStore) {
            saveToPlayerNbt(player, session);
        }
    }

    public static void saveToPlayerNbt(ServerPlayer player, RtsStorageSession session) {
        var root = RtsStorageSessionCodec.serialize(player, session);
        player.getPersistentData().put(RtsStorageSessionCodec.ROOT_KEY, root.copy());
        RtsStorageSessionStore.saveSession(player, root);
    }

    // ======================================================================
    // 生命周期
    // ======================================================================

    public static void onRtsEnabled(ServerPlayer player) {
        RtsStorageSession session = getOrCreate(player);
        RtsLinkedStorageResolver.sanitizeSessionDimension(player, session);
        RtsPageService.requestPage(player, session.browser.page, session.browser.search, session.browser.category, session.browser.sort, session.browser.ascending, false);
        ServerHistoryManager.sendSync(player);
    }

    public static void onRtsDisabled(ServerPlayer player) {
        RtsStorageSession session = getOrCreate(player);

        // Release mining resources (borrowed tool, break-stage particles)
        // WITHOUT cancelling the workflow entry or clearing the runtime mining
        // state. We want the mining operation to be resumable when the player
        // re-enables RTS mode and unpauses their thread.
        RtsMiningStateMachine.releaseMiningResources(player, session);

        // Pause any active (non-suspended, non-paused) workflow threads.
        // This ensures the player's in-progress operations are preserved
        // rather than silently abandoned when RTS mode is turned off.
        RtsWorkflowEngine.getInstance().pauseAllActive(player.getUUID(), true);

        // Do NOT clear placeBatchJobs here — the workflow entries are now
        // paused, and tickPlaceBatchJobs() skips paused entries. Keeping the
        // jobs in memory ensures that when the player re-enables RTS mode
        // and unpauses their threads, the placement work continues seamlessly.
        // If we cleared the jobs, they would be lost since the session stays
        // in SESSIONS and loadFromPersistentStorage() is only called once.
        RtsPathfindingService.cancel(player);
        RtsFunnelService.disableAndFlush(player, session);
        RtsMenuRemoteService.closeTracked(player, session);
        RtsMenuRemoteService.clearValidation(player, session);
        // Clear runtime BD network caches so their internal data can be
        // GC'd before the session object is dropped.
        session.cachedBdHandler = null;
        session.cachedBdFluidHandler = null;
        saveToPlayerNbt(player, session);
        // Free storage cache memory immediately instead of holding it
        // until the player logs out.
        RtsStorageTickService.INSTANCE.unregisterPlayer(player);
        RtsPageCore.clearCache(player.getUUID());
    }

    public static void onPlayerLogout(ServerPlayer player) {
        RtsPathfindingService.cancel(player);
        RtsStorageSession session = SESSIONS.get(player.getUUID());

        // Release mining resources (borrowed tool, break-stage particles)
        // without cancelling the workflow entries. The entries will be paused
        // below so the player sees them on rejoin.
        if (session != null) {
            RtsMiningStateMachine.releaseMiningResources(player, session);
        }

        // Pause any active (non-suspended, non-paused) workflow threads before
        // saving. When the player re-joins, these threads will appear paused
        // rather than silently missing — the player can unpause them to continue.
        RtsWorkflowEngine.getInstance().pauseAllActive(player.getUUID(), false);

        if (session != null) {
            // Save session data (including placement jobs) BEFORE clearing
            // active jobs. This ensures active placement jobs are persisted
            // and can be resumed when the player logs back in.
            saveToPlayerNbt(player, session);
            session.placement.placeBatchJobs.clear();
            RtsFunnelService.disableAndFlush(player, session);
            RtsMenuRemoteService.closeTracked(player, session);
            RtsMenuRemoteService.clearValidation(player, session);
            // Clear runtime BD network caches so their internal data can be
            // GC'd before the session object is dropped.
            session.cachedBdHandler = null;
            session.cachedBdFluidHandler = null;
        }
        SESSIONS.remove(player.getUUID());
        // Clean up storage cache
        RtsStorageTickService.INSTANCE.unregisterPlayer(player);
        RtsPageCore.clearCache(player.getUUID());

        // Clean up any active tickable pipelines for this player
        TickablePipelineRegistry.removeAll(player.getUUID());

        // Save workflow entries to the world save file so they survive
        // logout/login cycles within the same world save. We do NOT clear
        // the in-memory data here — that is handled by clearAllData() on
        // ServerStoppedEvent. Clearing now would cause the subsequent
        // saveAll() in onServerStopped to overwrite the file with empty
        // data, since all player entries would already be gone from memory.
        RtsWorkflowEngine.getInstance().saveAll(player.getServer());
    }

    public static void onPlayerTickPost(ServerPlayer player) {
        RtsStorageSession session = SESSIONS.get(player.getUUID());
        if (session == null) {
            return;
        }
        if (session.transfer.remoteMenuContainerId < 0
                && !RtsRemoteMenuCompat.isSupportedRemoteMenu(player.containerMenu)) {
            RtsMenuRemoteService.clearValidation(player, session);
        }
        if (session.transfer.remoteMenuContainerId >= 0
                && (player.containerMenu == null || player.containerMenu.containerId != session.transfer.remoteMenuContainerId)) {
            RtsMenuRemoteService.clearValidation(player, session);
        }
        RtsPlacementBatch.tickPlaceBatchJobs(player, session);
    }

    public static void warmCreativeTabCaches(MinecraftServer server) {
        if (server == null) {
            return;
        }
        synchronized (RtsSessionService.class) {
            RtsStoragePageBuilder.clearCreativeTabCacheState();
            ServerLevel level = server.overworld();
            if (level == null) {
                return;
            }
            RtsStoragePageBuilder.warmCreativeTabCacheMode(level, false);
            RtsStoragePageBuilder.warmCreativeTabCacheMode(level, true);
        }
    }

    public static void onPlayerTickPre(ServerPlayer player) {
        // RTS no longer spoofs player position for Sophisticated Storage menu validation.
    }

    public static void tickMining(MinecraftServer server) {
        // Tick storage cache refresh (every N ticks per player)
        var changes = RtsStorageTickService.INSTANCE.tick();

        // When cache detects item changes, push updated page to the client
        if (!changes.isEmpty()) {
            for (var entry : changes.entrySet()) {
                ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                if (player == null) continue;
                RtsStorageSession session = SESSIONS.get(entry.getKey());
                if (session == null) continue;
                // Increment data version so the page cache in RtsPageCore
                // knows the storage data has changed and should rebuild.
                session.transfer.pageDataVersion.incrementAndGet();
                if (!RtsProgressionManager.canUse(player, RtsFeature.STORAGE_BROWSER)) continue;
                RtsPageService.requestPage(player, session.browser.page, session.browser.search,
                        session.browser.category, session.browser.sort, session.browser.ascending);
                // 存储变化后自动尝试恢复挂起放置作业
                RtsPendingPlacementService.tryResumeAfterStorageChange(player);
            }
        }

        for (var entry : SESSIONS.entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                continue;
            }
            RtsStorageSession session = entry.getValue();
            RtsMiningStateMachine.tickActiveMining(player, session);
            RtsFunnelService.tick(player, session);
            RtsPlacedRecoveryService.tick(player, session);
        }

        // Tick all active tickable pipeline instances (ultimine/area-mine monitoring)
        TickablePipelineRegistry.tickAll();
    }

    // ======================================================================
    // 会话操作封装
    // ======================================================================

    public static BuilderMode getMode(ServerPlayer player) {
        RtsStorageSession session = SESSIONS.get(player.getUUID());
        return session == null ? BuilderMode.INTERACT : session.mode;
    }

    /**
     * 通知存储视图已过期。
     */
    public static void markStorageViewDirty(ServerPlayer player, RtsStorageSession session) {
        if (player == null || session == null) {
            return;
        }
        if (!RtsProgressionManager.canUse(player, RtsFeature.STORAGE_BROWSER)) {
            return;
        }
        if (session.transfer.storageViewDirty) {
            return;
        }
        session.transfer.storageViewDirty = true;
        PacketDistributor.sendToPlayer(player, new S2CRtsStorageDirtyPayload(true));
    }
}
