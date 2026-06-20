package com.rtsbuilding.rtsbuilding;


import com.mojang.logging.LogUtils;
import com.rtsbuilding.rtsbuilding.common.RtsBlocks;
import com.rtsbuilding.rtsbuilding.common.RtsCreativeTabs;
import com.rtsbuilding.rtsbuilding.common.RtsEntities;
import com.rtsbuilding.rtsbuilding.common.RtsItems;
import com.rtsbuilding.rtsbuilding.server.api.impl.RtsAPIImpl;
import com.rtsbuilding.rtsbuilding.server.camera.RtsCameraManager;
import com.rtsbuilding.rtsbuilding.server.feedback.RtsDamageFeedbackManager;
import com.rtsbuilding.rtsbuilding.server.history.ServerHistoryManager;
import com.rtsbuilding.rtsbuilding.server.pipeline.core.RtsPipelineRegistration;
import com.rtsbuilding.rtsbuilding.server.plugin.RtsPluginService;
import com.rtsbuilding.rtsbuilding.server.progression.RtsProgressionManager;
import com.rtsbuilding.rtsbuilding.server.service.*;
import com.rtsbuilding.rtsbuilding.server.workflow.core.RtsWorkflowEngine;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

@Mod(RtsbuildingMod.MODID)
public class RtsbuildingMod {

    public static final String MODID = "rtsbuilding";
    public static final Logger LOGGER = LogUtils.getLogger();

    public RtsbuildingMod(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);
        RtsEntities.register(modEventBus);
        RtsBlocks.register(modEventBus);
        RtsItems.register(modEventBus);
        RtsCreativeTabs.register(modEventBus);
        NeoForge.EVENT_BUS.register(this);
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC, "rts_building/rtsbuilding-common.toml");
        if (FMLEnvironment.dist == Dist.CLIENT) {
            com.rtsbuilding.rtsbuilding.client.bootstrap.RtsClientBootstrap.registerConfigUi(modContainer);
        }
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        ServiceRegistry.init();
        RtsAPIImpl.init();
        RtsPipelineRegistration.registerAll();
        LOGGER.info("RTSBuilding common setup complete");
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("HELLO from server starting");
    }

    @EventBusSubscriber(modid = RtsbuildingMod.MODID)
    static class GameEvents {
        @SubscribeEvent
        static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
            if (event.getEntity() instanceof ServerPlayer serverPlayer) {
                RtsCameraManager.cleanupOrphanCameras(serverPlayer.getServer());
                RtsDamageFeedbackManager.remember(serverPlayer);
                RtsProgressionManager.onPlayerLogin(serverPlayer);
                RtsPluginService.syncRelatedPlayers(serverPlayer);
                RtsWorkflowEngine.getInstance().loadPlayerFromStore(
                        serverPlayer.getServer(), serverPlayer);
            }
        }

        @SubscribeEvent
        static void onServerStarted(ServerStartedEvent event) {
            ServerTickOrchestrator.getInstance().warmCreativeTabCaches(event.getServer());
            RtsCameraManager.cleanupOrphanCameras(event.getServer());
        }

        @SubscribeEvent
        static void onServerStopped(ServerStoppedEvent event) {
            RtsWorkflowEngine.getInstance().saveAll(event.getServer());
            RtsWorkflowEngine.getInstance().clearAllData();
        }

        @SubscribeEvent
        static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
            if (event.getEntity() instanceof ServerPlayer serverPlayer) {
                RtsCameraManager.stopIfActive(serverPlayer);
                RtsDamageFeedbackManager.forget(serverPlayer);
                ServiceRegistry.getInstance().session().onPlayerLogout(serverPlayer);
                RtsProgressionManager.onPlayerLogout(serverPlayer);
                RtsPendingPlacementService.clearPlayerScanCache(serverPlayer.getUUID());
                RtsProgressRefresher.clearPlayerCache(serverPlayer.getUUID());
                RtsPluginService.syncRelatedPlayers(serverPlayer);
                ServerHistoryManager.clear(serverPlayer.getUUID());
            }
        }

        @SubscribeEvent
        static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
            if (event.getEntity() instanceof ServerPlayer serverPlayer) {
                RtsCameraManager.stopIfActive(serverPlayer);
                ServiceRegistry.getInstance().pathfinding().cancel(serverPlayer);
                RtsStorageTickService.INSTANCE.unregisterPlayer(serverPlayer);
            }
        }

        @SubscribeEvent
        static void onPlayerTickPost(PlayerTickEvent.Post event) {
            if (event.getEntity() instanceof ServerPlayer serverPlayer) {
                ServerTickOrchestrator.getInstance().onPlayerTickPost(serverPlayer);
                RtsDamageFeedbackManager.tick(serverPlayer);
            }
        }

        @SubscribeEvent
        static void onServerTick(ServerTickEvent.Post event) {
            ServerTickOrchestrator.getInstance().tickMining(event.getServer());
        }
    }
}
