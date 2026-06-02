package com.rtsbuilding.rtsbuilding.network.camera;

import com.rtsbuilding.rtsbuilding.server.RtsCameraManager;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public final class RtsCameraNetworkHandlers {
    private RtsCameraNetworkHandlers() {
    }

    public static void handleToggle(C2SRtsToggleCameraPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer) {
                RtsCameraManager.toggle(serverPlayer, payload.startAtPlayerHead());
            }
        });
    }

    public static void handleMove(C2SRtsCameraMovePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer) {
                RtsCameraManager.move(
                        serverPlayer,
                        payload.forward(),
                        payload.strafe(),
                        payload.vertical(),
                        payload.panX(),
                        payload.panY(),
                        payload.rotateX(),
                        payload.rotateY(),
                        payload.scroll(),
                        payload.rotateSteps(),
                        payload.fast());
            }
        });
    }
}