package com.rtsbuilding.rtsbuilding.network;

import com.rtsbuilding.rtsbuilding.client.ClientRtsController;
import com.rtsbuilding.rtsbuilding.server.RtsCameraManager;
import com.rtsbuilding.rtsbuilding.server.RtsStorageManager;

import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public final class RtsNetworkHandlers {
    private RtsNetworkHandlers() {
    }

    public static void handleToggle(C2SRtsToggleCameraPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer) {
                RtsCameraManager.toggle(serverPlayer);
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

    public static void handleSetMode(C2SRtsSetModePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer) {
                int modeId = payload.mode();
                var modes = com.rtsbuilding.rtsbuilding.client.BuilderMode.values();
                if (modeId < 0 || modeId >= modes.length) {
                    return;
                }
                RtsStorageManager.setMode(serverPlayer, modes[modeId]);
            }
        });
    }

    public static void handleSetFunnel(C2SRtsSetFunnelPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer) {
                RtsStorageManager.setFunnelEnabled(serverPlayer, payload.enabled());
            }
        });
    }

    public static void handleSetAutoStore(C2SRtsSetAutoStorePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer) {
                RtsStorageManager.setAutoStoreMinedDrops(serverPlayer, payload.enabled());
            }
        });
    }

    public static void handleLinkStorage(C2SRtsLinkStoragePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer) {
                RtsStorageManager.linkStorage(serverPlayer, payload.pos());
            }
        });
    }

    public static void handleRequestStoragePage(C2SRtsRequestStoragePagePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer) {
                RtsStorageManager.requestPage(
                        serverPlayer,
                        payload.page(),
                        payload.search(),
                        payload.category(),
                        RtsStorageSort.byId(payload.sort()),
                        payload.ascending());
            }
        });
    }

    public static void handlePlace(C2SRtsPlacePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer) {
                Direction face = Direction.from3DDataValue(payload.face());
                RtsStorageManager.placeSelected(
                        serverPlayer,
                        payload.clickedPos(),
                        face,
                        payload.hitX(),
                        payload.hitY(),
                        payload.hitZ(),
                        payload.rotateSteps(),
                        payload.forcePlace(),
                        payload.skipIfOccupied(),
                        payload.itemId(),
                        payload.rayOriginX(),
                        payload.rayOriginY(),
                        payload.rayOriginZ(),
                        payload.rayDirX(),
                        payload.rayDirY(),
                        payload.rayDirZ());
            }
        });
    }

    public static void handlePlaceFluid(C2SRtsPlaceFluidPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer) {
                Direction face = Direction.from3DDataValue(payload.face());
                RtsStorageManager.placeFluid(
                        serverPlayer,
                        payload.clickedPos(),
                        face,
                        payload.hitX(),
                        payload.hitY(),
                        payload.hitZ(),
                        payload.forcePlace(),
                        payload.fluidId(),
                        payload.rayOriginX(),
                        payload.rayOriginY(),
                        payload.rayOriginZ(),
                        payload.rayDirX(),
                        payload.rayDirY(),
                        payload.rayDirZ());
            }
        });
    }

    public static void handleStoreFluid(C2SRtsStoreFluidPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer) {
                RtsStorageManager.storeFluidFromContainer(
                        serverPlayer,
                        payload.sourceType(),
                        payload.toolSlot(),
                        payload.itemId());
            }
        });
    }

    public static void handleInteract(C2SRtsInteractPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer) {
                Direction face = Direction.from3DDataValue(payload.face());
                RtsStorageManager.interactTarget(
                        serverPlayer,
                        payload.entityId(),
                        payload.clickedPos(),
                        face,
                        payload.hitX(),
                        payload.hitY(),
                        payload.hitZ(),
                        payload.sourceType(),
                        payload.toolSlot(),
                        payload.itemId(),
                        payload.rayOriginX(),
                        payload.rayOriginY(),
                        payload.rayOriginZ(),
                        payload.rayDirX(),
                        payload.rayDirY(),
                        payload.rayDirZ());
            }
        });
    }

    public static void handleQuickDrop(C2SRtsQuickDropPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer) {
                RtsStorageManager.quickDropLinkedItem(
                        serverPlayer,
                        payload.itemId(),
                        payload.amount(),
                        payload.dropX(),
                        payload.dropY(),
                        payload.dropZ());
            }
        });
    }

    public static void handleBreak(C2SRtsBreakPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer) {
                Direction face = Direction.from3DDataValue(payload.face());
                RtsStorageManager.breakPlaced(serverPlayer, payload.pos(), face, payload.allowAdjacentFallback());
            }
        });
    }

    public static void handleMine(C2SRtsMinePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer) {
                Direction face = Direction.from3DDataValue(payload.face());
                RtsStorageManager.mine(serverPlayer, payload.pos(), face, payload.start(), payload.toolSlot());
            }
        });
    }

    public static void handleFunnelTarget(C2SRtsFunnelTargetPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer) {
                RtsStorageManager.updateFunnelTarget(serverPlayer, payload.target());
            }
        });
    }

    public static void handleLinkedPickup(C2SRtsLinkedPickupPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer) {
                RtsStorageManager.pickupLinkedToCarried(serverPlayer, payload.itemId(), payload.amount());
            }
        });
    }

    public static void handleReturnCarried(C2SRtsReturnCarriedPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer) {
                RtsStorageManager.returnCarriedToLinked(serverPlayer, payload.itemId(), payload.amount());
            }
        });
    }

    public static void handleOpenCraftTerminal(C2SRtsOpenCraftTerminalPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer) {
                RtsStorageManager.openCraftTerminal(serverPlayer);
            }
        });
    }

    public static void handleImportMenuSlot(C2SRtsImportMenuSlotPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer) {
                RtsStorageManager.importMenuSlotToLinked(serverPlayer, payload.menuSlot());
            }
        });
    }

    public static void handleCraftRefill(C2SRtsCraftRefillPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer) {
                RtsStorageManager.refillCurrentCraftGridFromBlueprintIds(serverPlayer, payload.blueprintItemIds());
            }
        });
    }

    public static void handleJeiTransfer(C2SRtsJeiTransferPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer) {
                RtsStorageManager.applyJeiTransfer(serverPlayer, payload.recipeId(), payload.maxTransfer(), payload.clearGridFirst());
            }
        });
    }

    public static void handleQuestDetect(C2SRtsQuestDetectPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer) {
                RtsStorageManager.detectQuests(serverPlayer, payload.mode());
            }
        });
    }

    public static void handleCameraState(S2CRtsCameraStatePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientRtsController.get().applyServerCameraState(payload));
    }

    public static void handleStoragePage(S2CRtsStoragePagePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientRtsController.get().applyStoragePage(payload));
    }

    public static void handleMineProgress(S2CRtsMineProgressPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientRtsController.get().applyMineProgress(payload));
    }
}
