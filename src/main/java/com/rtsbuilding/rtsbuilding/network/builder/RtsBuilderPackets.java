package com.rtsbuilding.rtsbuilding.network.builder;

import com.rtsbuilding.rtsbuilding.network.RtsClientPayloadBridge;

import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Registers RTS placement, mining, interaction, and quick-drop packets.
 *
 * This class groups packet registration only; payload ids, codecs, and packet
 * directions stay in the payload records.
 */
public final class RtsBuilderPackets {
    private RtsBuilderPackets() {
    }

    public static void register(PayloadRegistrar registrar) {
        registrar.playToServer(
                C2SRtsSetModePayload.TYPE,
                C2SRtsSetModePayload.STREAM_CODEC,
                RtsBuilderNetworkHandlers::handleSetMode);

        registrar.playToServer(
                C2SRtsRotateBlockPayload.TYPE,
                C2SRtsRotateBlockPayload.STREAM_CODEC,
                RtsBuilderNetworkHandlers::handleRotateBlock);

        registrar.playToServer(
                C2SRtsPlacePayload.TYPE,
                C2SRtsPlacePayload.STREAM_CODEC,
                RtsBuilderNetworkHandlers::handlePlace);

        registrar.playToServer(
                C2SRtsPlaceBatchPayload.TYPE,
                C2SRtsPlaceBatchPayload.STREAM_CODEC,
                RtsBuilderNetworkHandlers::handlePlaceBatch);

        registrar.playToServer(
                C2SRtsPlaceFluidPayload.TYPE,
                C2SRtsPlaceFluidPayload.STREAM_CODEC,
                RtsBuilderNetworkHandlers::handlePlaceFluid);

        registrar.playToServer(
                C2SRtsStoreFluidPayload.TYPE,
                C2SRtsStoreFluidPayload.STREAM_CODEC,
                RtsBuilderNetworkHandlers::handleStoreFluid);

        registrar.playToServer(
                C2SRtsInteractPayload.TYPE,
                C2SRtsInteractPayload.STREAM_CODEC,
                RtsBuilderNetworkHandlers::handleInteract);

        registrar.playToServer(
                C2SRtsQuickDropPayload.TYPE,
                C2SRtsQuickDropPayload.STREAM_CODEC,
                RtsBuilderNetworkHandlers::handleQuickDrop);

        registrar.playToServer(
                C2SRtsBreakPayload.TYPE,
                C2SRtsBreakPayload.STREAM_CODEC,
                RtsBuilderNetworkHandlers::handleBreak);

        registrar.playToServer(
                C2SRtsMinePayload.TYPE,
                C2SRtsMinePayload.STREAM_CODEC,
                RtsBuilderNetworkHandlers::handleMine);

        registrar.playToServer(
                C2SRtsUltiminePayload.TYPE,
                C2SRtsUltiminePayload.STREAM_CODEC,
                RtsBuilderNetworkHandlers::handleUltimine);

        registrar.playToServer(
                C2SRtsAreaMinePayload.TYPE,
                C2SRtsAreaMinePayload.STREAM_CODEC,
                RtsBuilderNetworkHandlers::handleAreaMine);

        registrar.playToServer(
                C2SRtsAreaDestroyPayload.TYPE,
                C2SRtsAreaDestroyPayload.STREAM_CODEC,
                RtsBuilderNetworkHandlers::handleAreaDestroy);

        registrar.playToClient(
                S2CRtsMineProgressPayload.TYPE,
                S2CRtsMineProgressPayload.STREAM_CODEC,
                RtsClientPayloadBridge::handleMineProgress);

        registrar.playToClient(
                S2CRtsPlaceAnimationPayload.TYPE,
                S2CRtsPlaceAnimationPayload.STREAM_CODEC,
                RtsClientPayloadBridge::handlePlaceAnimation);

        registrar.playToClient(
                S2CRtsBreakAnimationPayload.TYPE,
                S2CRtsBreakAnimationPayload.STREAM_CODEC,
                RtsClientPayloadBridge::handleBreakAnimation);

        registrar.playToClient(
                S2CRtsUltimineProgressPayload.TYPE,
                S2CRtsUltimineProgressPayload.STREAM_CODEC,
                RtsClientPayloadBridge::handleUltimineProgress);

        // ===== Undo =====

        registrar.playToServer(
                C2SRtsUndoPayload.TYPE,
                C2SRtsUndoPayload.STREAM_CODEC,
                RtsBuilderNetworkHandlers::handleUndo);

        registrar.playToClient(
                S2CRtsHistorySyncPayload.TYPE,
                S2CRtsHistorySyncPayload.STREAM_CODEC,
                RtsClientPayloadBridge::handleHistorySync);
    }
}
