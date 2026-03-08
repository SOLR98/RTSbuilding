package com.rtsbuilding.rtsbuilding.network;

import com.rtsbuilding.rtsbuilding.RtsbuildingMod;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@EventBusSubscriber(modid = RtsbuildingMod.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class RtsPayloadRegistrar {
    private RtsPayloadRegistrar() {
    }

    @SubscribeEvent
    public static void register(final RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");

        registrar.playToServer(
                C2SRtsToggleCameraPayload.TYPE,
                C2SRtsToggleCameraPayload.STREAM_CODEC,
                RtsNetworkHandlers::handleToggle);

        registrar.playToServer(
                C2SRtsCameraMovePayload.TYPE,
                C2SRtsCameraMovePayload.STREAM_CODEC,
                RtsNetworkHandlers::handleMove);

        registrar.playToServer(
                C2SRtsSetModePayload.TYPE,
                C2SRtsSetModePayload.STREAM_CODEC,
                RtsNetworkHandlers::handleSetMode);

        registrar.playToServer(
                C2SRtsSetFunnelPayload.TYPE,
                C2SRtsSetFunnelPayload.STREAM_CODEC,
                RtsNetworkHandlers::handleSetFunnel);

        registrar.playToServer(
                C2SRtsSetAutoStorePayload.TYPE,
                C2SRtsSetAutoStorePayload.STREAM_CODEC,
                RtsNetworkHandlers::handleSetAutoStore);

        registrar.playToServer(
                C2SRtsLinkStoragePayload.TYPE,
                C2SRtsLinkStoragePayload.STREAM_CODEC,
                RtsNetworkHandlers::handleLinkStorage);

        registrar.playToServer(
                C2SRtsRequestStoragePagePayload.TYPE,
                C2SRtsRequestStoragePagePayload.STREAM_CODEC,
                RtsNetworkHandlers::handleRequestStoragePage);

        registrar.playToServer(
                C2SRtsPlacePayload.TYPE,
                C2SRtsPlacePayload.STREAM_CODEC,
                RtsNetworkHandlers::handlePlace);

        registrar.playToServer(
                C2SRtsPlaceFluidPayload.TYPE,
                C2SRtsPlaceFluidPayload.STREAM_CODEC,
                RtsNetworkHandlers::handlePlaceFluid);

        registrar.playToServer(
                C2SRtsStoreFluidPayload.TYPE,
                C2SRtsStoreFluidPayload.STREAM_CODEC,
                RtsNetworkHandlers::handleStoreFluid);

        registrar.playToServer(
                C2SRtsInteractPayload.TYPE,
                C2SRtsInteractPayload.STREAM_CODEC,
                RtsNetworkHandlers::handleInteract);

        registrar.playToServer(
                C2SRtsQuickDropPayload.TYPE,
                C2SRtsQuickDropPayload.STREAM_CODEC,
                RtsNetworkHandlers::handleQuickDrop);

        registrar.playToServer(
                C2SRtsBreakPayload.TYPE,
                C2SRtsBreakPayload.STREAM_CODEC,
                RtsNetworkHandlers::handleBreak);

        registrar.playToServer(
                C2SRtsMinePayload.TYPE,
                C2SRtsMinePayload.STREAM_CODEC,
                RtsNetworkHandlers::handleMine);

        registrar.playToServer(
                C2SRtsFunnelTargetPayload.TYPE,
                C2SRtsFunnelTargetPayload.STREAM_CODEC,
                RtsNetworkHandlers::handleFunnelTarget);

        registrar.playToServer(
                C2SRtsLinkedPickupPayload.TYPE,
                C2SRtsLinkedPickupPayload.STREAM_CODEC,
                RtsNetworkHandlers::handleLinkedPickup);

        registrar.playToServer(
                C2SRtsReturnCarriedPayload.TYPE,
                C2SRtsReturnCarriedPayload.STREAM_CODEC,
                RtsNetworkHandlers::handleReturnCarried);

        registrar.playToServer(
                C2SRtsOpenCraftTerminalPayload.TYPE,
                C2SRtsOpenCraftTerminalPayload.STREAM_CODEC,
                RtsNetworkHandlers::handleOpenCraftTerminal);

        registrar.playToServer(
                C2SRtsImportMenuSlotPayload.TYPE,
                C2SRtsImportMenuSlotPayload.STREAM_CODEC,
                RtsNetworkHandlers::handleImportMenuSlot);

        registrar.playToServer(
                C2SRtsCraftRefillPayload.TYPE,
                C2SRtsCraftRefillPayload.STREAM_CODEC,
                RtsNetworkHandlers::handleCraftRefill);

        registrar.playToServer(
                C2SRtsJeiTransferPayload.TYPE,
                C2SRtsJeiTransferPayload.STREAM_CODEC,
                RtsNetworkHandlers::handleJeiTransfer);

        registrar.playToServer(
                C2SRtsQuestDetectPayload.TYPE,
                C2SRtsQuestDetectPayload.STREAM_CODEC,
                RtsNetworkHandlers::handleQuestDetect);

        registrar.playToClient(
                S2CRtsCameraStatePayload.TYPE,
                S2CRtsCameraStatePayload.STREAM_CODEC,
                RtsNetworkHandlers::handleCameraState);

        registrar.playToClient(
                S2CRtsStoragePagePayload.TYPE,
                S2CRtsStoragePagePayload.STREAM_CODEC,
                RtsNetworkHandlers::handleStoragePage);

        registrar.playToClient(
                S2CRtsMineProgressPayload.TYPE,
                S2CRtsMineProgressPayload.STREAM_CODEC,
                RtsNetworkHandlers::handleMineProgress);
    }
}
