package com.rtsbuilding.rtsbuilding.network.craft;

import com.rtsbuilding.rtsbuilding.server.RtsStorageManager;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public final class RtsCraftNetworkHandlers {
    private RtsCraftNetworkHandlers() {
    }

    public static void handleRequestCraftables(C2SRtsRequestCraftablesPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer) {
                RtsStorageManager.requestCraftables(
                        serverPlayer,
                        payload.search(),
                        payload.showUnavailable(),
                        payload.offset(),
                        payload.limit(),
                        payload.pinyinSearchEnabled(),
                        payload.localizedSearchMatches());
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

    public static void handleCraftRefill(C2SRtsCraftRefillPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer) {
                RtsStorageManager.refillCurrentCraftGridFromBlueprintIds(
                        serverPlayer,
                        payload.blueprintItemIds(),
                        payload.craftedItemId(),
                        payload.craftedCount());
            }
        });
    }

    public static void handleCraftRecipe(C2SRtsCraftRecipePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer) {
                RtsStorageManager.craftRecipeToLinked(serverPlayer, payload.recipeId(), payload.craftCount());
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
}