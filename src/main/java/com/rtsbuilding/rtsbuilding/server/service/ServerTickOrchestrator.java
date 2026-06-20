package com.rtsbuilding.rtsbuilding.server.service;

import com.rtsbuilding.rtsbuilding.compat.remote.RtsRemoteMenuCompat;
import com.rtsbuilding.rtsbuilding.server.pipeline.core.TickablePipelineRegistry;
import com.rtsbuilding.rtsbuilding.progression.RtsFeature;
import com.rtsbuilding.rtsbuilding.server.progression.RtsProgressionManager;
import com.rtsbuilding.rtsbuilding.server.service.destruction.RtsDestructionBatch;
import com.rtsbuilding.rtsbuilding.server.service.mining.RtsMiningStateMachine;
import com.rtsbuilding.rtsbuilding.server.service.placement.RtsPlacementBatch;
import com.rtsbuilding.rtsbuilding.server.storage.RtsStoragePageBuilder;
import com.rtsbuilding.rtsbuilding.server.storage.session.RtsStorageSession;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public final class ServerTickOrchestrator {

    private static final ServerTickOrchestrator INSTANCE = new ServerTickOrchestrator();

    private ServerTickOrchestrator() {
    }

    public static ServerTickOrchestrator getInstance() {
        return INSTANCE;
    }

    public void onPlayerTickPost(ServerPlayer player) {
        var registry = ServiceRegistry.getInstance();
        RtsStorageSession session = registry.session().getIfPresent(player);
        if (session == null) {
            return;
        }
        if (session.transfer.remoteMenuContainerId < 0
                && !RtsRemoteMenuCompat.isSupportedRemoteMenu(player.containerMenu)) {
            RtsRemoteMenuService.clearValidation(player, session);
        }
        if (session.transfer.remoteMenuContainerId >= 0
                && (player.containerMenu == null || player.containerMenu.containerId != session.transfer.remoteMenuContainerId)) {
            RtsRemoteMenuService.clearValidation(player, session);
        }
        RtsPlacementBatch.tickPlaceBatchJobs(player, session);
        RtsDestructionBatch.tickDestroyJobs(player, session);
    }

    public void tickMining(MinecraftServer server) {
        var registry = ServiceRegistry.getInstance();
        var sessionService = registry.session();
        var funnelService = registry.funnel();
        var serviceOp = registry.serviceOp();

        var changes = RtsStorageTickService.INSTANCE.tick();

        if (!changes.isEmpty()) {
            for (var entry : changes.entrySet()) {
                ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                if (player == null) continue;
                RtsStorageSession session = sessionService.getIfPresent(player);
                if (session == null) continue;
                session.transfer.pageDataVersion.incrementAndGet();
                if (!RtsProgressionManager.canUse(player, RtsFeature.STORAGE_BROWSER)) continue;
                serviceOp.refreshPage(player, session);
                RtsPendingPlacementService.tryResumeAfterStorageChange(player);
                RtsDestructionBatch.tryResumePendingDestroyJobs(player, session);
            }
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            RtsStorageSession session = sessionService.getIfPresent(player);
            if (session == null) continue;
            RtsMiningStateMachine.tickActiveMining(player, session);
            funnelService.tick(player, session);
            RtsPlacedRecoveryService.tick(player, session);
        }

        TickablePipelineRegistry.tickAll();
    }

    public void warmCreativeTabCaches(MinecraftServer server) {
        if (server == null) {
            return;
        }
        synchronized (ServerTickOrchestrator.class) {
            RtsStoragePageBuilder.clearCreativeTabCacheState();
            ServerLevel level = server.overworld();
            if (level == null) {
                return;
            }
            RtsStoragePageBuilder.warmCreativeTabCacheMode(level, false);
            RtsStoragePageBuilder.warmCreativeTabCacheMode(level, true);
        }
    }
}
