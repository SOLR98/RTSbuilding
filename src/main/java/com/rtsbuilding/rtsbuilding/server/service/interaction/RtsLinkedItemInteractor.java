package com.rtsbuilding.rtsbuilding.server.service.interaction;

import com.rtsbuilding.rtsbuilding.server.service.transfer.RtsTransferExtractor;
import com.rtsbuilding.rtsbuilding.server.service.transfer.RtsTransferInserter;
import com.rtsbuilding.rtsbuilding.server.service.RtsStorageTickService;
import com.rtsbuilding.rtsbuilding.server.storage.LinkedHandler;
import com.rtsbuilding.rtsbuilding.server.storage.RtsLinkedStorageResolver;
import com.rtsbuilding.rtsbuilding.server.storage.RtsStorageSession;
import com.rtsbuilding.rtsbuilding.server.util.InteractionHelper;
import com.rtsbuilding.rtsbuilding.server.util.TemporaryContextSwitcher;
import com.rtsbuilding.rtsbuilding.server.util.TemporaryContextSwitcher.RayContext;
import com.rtsbuilding.rtsbuilding.server.util.TemporaryContextSwitcher.UseOnOutcome;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.List;

/**
 * Handles RTS remote interaction using a pinned item extracted from the
 * player's linked storage system.
 *
 * <p>Extracts one item from the linked network, stores the current main-hand
 * item back to the network, places the extracted item on the main hand, then
 * uses it against the target.  The remainder naturally stays on the main hand.
 */
public final class RtsLinkedItemInteractor {

    private static final double REMOTE_POV_BLOCK_REACH = 4.0D;

    private RtsLinkedItemInteractor() {
    }

    public static InteractionResult interactWithLinkedItem(ServerPlayer player, ServerLevel level, RtsStorageSession session,
            Entity targetEntity, BlockHitResult blockHit, Vec3 hit, String itemId, RayContext rayContext) {
        if (itemId == null || itemId.isBlank() || !RtsLinkedStorageResolver.hasAnyStorage(player, session)) {
            return InteractionResult.PASS;
        }

        List<LinkedHandler> activeLinked = RtsLinkedStorageResolver.resolveLinkedHandlers(player, session);
        if (activeLinked.isEmpty()) {
            return InteractionResult.PASS;
        }

        List<IItemHandler> extractHandlers = RtsLinkedStorageResolver.itemHandlersForExtract(activeLinked);
        List<IItemHandler> insertHandlers = RtsLinkedStorageResolver.itemHandlersForInsert(activeLinked);

        ResourceLocation id = ResourceLocation.tryParse(itemId);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            return InteractionResult.PASS;
        }

        ItemStack extracted = RtsTransferExtractor.extractOneFromNetwork(extractHandlers, player, BuiltInRegistries.ITEM.get(id));
        if (extracted.isEmpty()) {
            return InteractionResult.PASS;
        }

        ItemStack previousMainHand = player.getMainHandItem();
        if (!previousMainHand.isEmpty()) {
            ItemStack stored = RtsTransferInserter.storeToLinkedWithFallbackPreferExisting(insertHandlers, player, previousMainHand.copy());
            if (!stored.isEmpty()) {
                return InteractionResult.PASS;
            }
        }
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, extracted);

        Vec3 interactionPos = InteractionHelper.resolveInteractionPosition(targetEntity, blockHit, hit);
        UseOnOutcome outcome = TemporaryContextSwitcher.withTemporaryUseItemContext(
                player,
                interactionPos,
                hit,
                rayContext,
                REMOTE_POV_BLOCK_REACH,
                () -> {
            if (targetEntity != null) {
                return InteractionHelper.useItemOnEntityWithMainHand(player, level, extracted, targetEntity, hit);
            }
            UseOnOutcome primaryOn = InteractionHelper.useMainHandItemOnBlock(player, level, blockHit, false);
            if (primaryOn.result().consumesAction()) {
                return primaryOn;
            }
            ItemStack afterPrimaryOn = player.getMainHandItem().isEmpty()
                    ? extracted.copy() : player.getMainHandItem().copy();

            UseOnOutcome primaryUse = InteractionHelper.useMainHandItemInAir(player, level, false);
            if (primaryUse.result().consumesAction()) {
                return primaryUse;
            }
            ItemStack afterPrimaryUse = player.getMainHandItem().isEmpty()
                    ? afterPrimaryOn : player.getMainHandItem().copy();

            UseOnOutcome secondaryOn = InteractionHelper.useMainHandItemOnBlock(player, level, blockHit, true);
            if (secondaryOn.result().consumesAction()) {
                return secondaryOn;
            }
            ItemStack afterSecondaryOn = player.getMainHandItem().isEmpty()
                    ? afterPrimaryUse : player.getMainHandItem().copy();
            return InteractionHelper.useMainHandItemInAir(player, level, true);
                });

        RtsStorageTickService.INSTANCE.forceRefresh(player);
        session.transfer.pageDataVersion.incrementAndGet();
        return outcome.result();
    }
}
