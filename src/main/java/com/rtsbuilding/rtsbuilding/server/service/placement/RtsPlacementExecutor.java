package com.rtsbuilding.rtsbuilding.server.service.placement;

import com.rtsbuilding.rtsbuilding.network.storage.S2CRtsStoragePagePayload;
import com.rtsbuilding.rtsbuilding.progression.RtsFeature;
import com.rtsbuilding.rtsbuilding.server.data.PlacedBlockTrackerData;
import com.rtsbuilding.rtsbuilding.server.progression.RtsProgressionManager;
import com.rtsbuilding.rtsbuilding.server.service.RtsPageService;
import com.rtsbuilding.rtsbuilding.server.service.RtsRemoteMenuService;
import com.rtsbuilding.rtsbuilding.server.service.RtsSessionService;
import com.rtsbuilding.rtsbuilding.server.service.SoundService;
import com.rtsbuilding.rtsbuilding.server.service.transfer.RtsTransferInserter;
import com.rtsbuilding.rtsbuilding.server.storage.LinkedHandler;
import com.rtsbuilding.rtsbuilding.server.storage.RtsLinkedStorageResolver;
import com.rtsbuilding.rtsbuilding.server.storage.RtsStoragePageBuilder;
import com.rtsbuilding.rtsbuilding.server.storage.RtsStorageSession;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.List;

/**
 * Single interactive placement execution for RTS storage builder.
 *
 * <p>This helper owns the core {@link #placeSelectedInternal} state machine
 * that handles a single remote placement — whether from the player's main
 * hand or from a selected storage item. It manages the full placement flow:
 * skip-if-occupied prechecks, use-on-block attempt, use-item-as-fallback
 * attempt, extraction from network/linked storage, placement detection,
 * rotation, and recent-item recording.
 *
 * <p>It deliberately does not manage batch-job queuing, pre-resolved
 * quick-build placement, item extraction primitives, or sound/effect
 * dispatch — those responsibilities live in their dedicated helpers.
 */
public final class RtsPlacementExecutor {
    private static final double REMOTE_POV_BLOCK_REACH = 4.0D;

    private RtsPlacementExecutor() {
    }

    /**
     * Attempts to place a single block at the given position using the remote
     * RTS placement machinery.
     *
     * <p>When {@code itemId} is blank or null the method uses the player's
     * main-hand item (interactive placement). When {@code itemId} is set the
     * method extracts the item from linked storage or player inventory.
     *
     * @param player              the server player
     * @param session             the player's RTS storage session
     * @param clickedPos          target block position
     * @param face                clicked face
     * @param hitX,hitY,hitZ      hit-location coordinates
     * @param rotateSteps         number of 90-degree clockwise rotations
     * @param forcePlace          whether to simulate shift-click (force place)
     * @param skipIfOccupied      skip positions that are already occupied
     * @param itemId              storage item id (blank/null for main-hand)
     * @param itemPrototype       preferred prototype stack for extraction
     * @param rayDirY ray context for reach extension
     * @param quickBuild          {@code true} when this is part of a quick-build batch
     * @param refreshStoragePage  {@code true} to trigger a storage page refresh
     * @param sendRemoteHint      {@code true} to send the menu-open hint packet
     * @return {@code true} if the position was handled and the batch should
     *         continue, {@code false} to abort the current batch job
     */
    public static boolean placeSelectedInternal(ServerPlayer player, RtsStorageSession session, BlockPos clickedPos,
                                                Direction face, double hitX, double hitY, double hitZ, byte rotateSteps, boolean forcePlace,
                                                boolean skipIfOccupied, String itemId, ItemStack itemPrototype, double rayOriginX, double rayOriginY,
                                                double rayOriginZ, double rayDirX, double rayDirY, double rayDirZ, boolean quickBuild,
                                                boolean forceEmptyHand, boolean refreshStoragePage, boolean sendRemoteHint) {
        if (!RtsProgressionManager.canUse(player, RtsFeature.REMOTE_PLACE)) {
            return false;
        }
        if (session == null || !RtsLinkedStorageResolver.canAccessWorldTarget(player, clickedPos) || face == null) {
            return false;
        }
        RtsLinkedStorageResolver.sanitizeSessionDimension(player, session);
        boolean useSelectedStorageItem = itemId != null && !itemId.isBlank();

        ServerLevel level = player.serverLevel();
        Vec3 hitLocation = new Vec3(hitX, hitY, hitZ);
        BlockHitResult hit = new BlockHitResult(hitLocation, face, clickedPos, false);
        Vec3 interactionPos = InteractionHelper.resolveInteractionPosition(null, hit, hitLocation);
        TemporaryContextSwitcher.RayContext rayContext = TemporaryContextSwitcher.parseRayContext(
                rayOriginX, rayOriginY, rayOriginZ,
                rayDirX, rayDirY, rayDirZ);
        if (sendRemoteHint) {
            RtsRemoteMenuService.sendRemoteMenuOpenHint(player, clickedPos);
        }

        if (!useSelectedStorageItem) {
            if (forceEmptyHand) {
                return placeWithForcedEmptyHand(player, session, level, clickedPos, hit, interactionPos, rayContext,
                        forcePlace);
            }
            return false;
        }

        return placeWithStorageItem(player, session, level, clickedPos, face, hit, interactionPos, rayContext,
                rotateSteps, skipIfOccupied, forcePlace, itemId, itemPrototype, refreshStoragePage);
    }

    private static boolean placeWithForcedEmptyHand(ServerPlayer player, RtsStorageSession session, ServerLevel level,
            BlockPos clickedPos, BlockHitResult hit, Vec3 interactionPos, TemporaryContextSwitcher.RayContext rayContext,
            boolean forcePlace) {
        AbstractContainerMenu menuBeforeEmptyUse = player.containerMenu;
        TemporaryContextSwitcher.UseOnOutcome emptyUse = TemporaryContextSwitcher.withTemporaryUseItemContext(
                player,
                interactionPos,
                hit.getLocation(),
                rayContext,
                REMOTE_POV_BLOCK_REACH,
                () -> InteractionHelper.useItemOnWithMainHand(player, level, ItemStack.EMPTY, hit, forcePlace));
        AbstractContainerMenu menuAfterEmptyUse = player.containerMenu;
        if (menuAfterEmptyUse != menuBeforeEmptyUse) {
            RtsRemoteMenuService.markRemoteMenuOpen(player, session, menuAfterEmptyUse, clickedPos);
            return false;
        }

        if (emptyUse.result().consumesAction()) {
            RtsSessionService.saveToPlayerNbt(player, session);
            return true;
        }

        AbstractContainerMenu menuBeforeEmptyFallback = player.containerMenu;
        TemporaryContextSwitcher.UseOnOutcome emptyFallback = TemporaryContextSwitcher.withTemporaryUseItemContext(
                player,
                interactionPos,
                hit.getLocation(),
                rayContext,
                REMOTE_POV_BLOCK_REACH,
                () -> InteractionHelper.useItemWithMainHand(player, level, ItemStack.EMPTY, forcePlace));
        AbstractContainerMenu menuAfterEmptyFallback = player.containerMenu;
        if (menuAfterEmptyFallback != menuBeforeEmptyFallback) {
            RtsRemoteMenuService.markRemoteMenuOpen(player, session, menuAfterEmptyFallback, clickedPos);
            return false;
        }
        if (emptyFallback.result().consumesAction()) {
            RtsSessionService.saveToPlayerNbt(player, session);
            return true;
        }
        return false;
    }


    private static boolean placeWithStorageItem(ServerPlayer player, RtsStorageSession session, ServerLevel level,
            BlockPos clickedPos, Direction face, BlockHitResult hit,
            Vec3 interactionPos, TemporaryContextSwitcher.RayContext rayContext, byte rotateSteps, boolean skipIfOccupied,
            boolean forcePlace, String itemId, ItemStack itemPrototype, boolean refreshStoragePage) {
        List<LinkedHandler> activeLinked = RtsLinkedStorageResolver.resolveLinkedHandlers(player, session);
        boolean includePlayerMainInventory = RtsStoragePageBuilder.shouldIncludePlayerMainInventoryInStorageView(player, session);
        boolean creativeSource = player.isCreative();
        if (activeLinked.isEmpty() && !includePlayerMainInventory && !creativeSource) {
            return false;
        }

        List<IItemHandler> extractHandlers = RtsLinkedStorageResolver.itemHandlersForExtract(activeLinked);
        List<IItemHandler> insertHandlers = RtsLinkedStorageResolver.itemHandlersForInsert(activeLinked);

        ResourceLocation id = ResourceLocation.tryParse(itemId);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            return false;
        }

        Item item = BuiltInRegistries.ITEM.get(id);
        ItemStack preferredStack = RtsPlacementExtractor.sanitizePrototype(itemId, itemPrototype);

        if (!(item instanceof BlockItem blockItem)) {
            return false;
        }

        if (skipIfOccupied) {
            if (!level.hasChunkAt(clickedPos) || !level.getBlockState(clickedPos).canBeReplaced()) {
                RtsPlacementHelper.requestSessionPage(player, session, refreshStoragePage);
                return true;
            }
        }

        ItemStack templateStack = preferredStack.isEmpty() ? new ItemStack(item) : preferredStack.copy();
        templateStack.setCount(1);
        BlockPlaceContext context = new BlockPlaceContext(level, player, InteractionHand.MAIN_HAND,
                templateStack, new net.minecraft.world.phys.BlockHitResult(
                        hit.getLocation(), face, clickedPos, hit.isInside()));
        BlockState targetState = blockItem.getBlock().getStateForPlacement(context);
        if (targetState == null) {
            RtsPlacementHelper.requestSessionPage(player, session, refreshStoragePage);
            return false;
        }
        targetState = RtsPlacementHelper.rotateState(targetState, rotateSteps);

        if (skipIfOccupied) {
            if (!level.getBlockState(clickedPos).canBeReplaced()
                    || !targetState.canSurvive(level, clickedPos)
                    || !level.isUnobstructed(targetState, clickedPos,
                            net.minecraft.world.phys.shapes.CollisionContext.of(player))) {
                RtsPlacementHelper.requestSessionPage(player, session, refreshStoragePage);
                return true;
            }
        }

        ItemStack extracted = creativeSource
                ? RtsPlacementExtractor.creativeStack(item, preferredStack)
                : includePlayerMainInventory
                        ? RtsPlacementExtractor.extractSelectedFromNetwork(extractHandlers, player, item, preferredStack)
                        : RtsPlacementExtractor.extractSelectedFromLinked(extractHandlers, item, preferredStack);
        if (extracted.isEmpty()) {
            RtsPlacementHelper.requestSessionPage(player, session, refreshStoragePage);
            return false;
        }
        ItemStack placementStack = extracted.copy();
        placementStack.setCount(1);

        boolean placed = level.setBlock(clickedPos, targetState, 3);
        if (!placed) {
            if (!creativeSource && !extracted.isEmpty()) {
                RtsTransferInserter.refundToLinked(insertHandlers, player, extracted);
            }
            RtsPlacementHelper.requestSessionPage(player, session, refreshStoragePage);
            return false;
        }

        BlockState placedState = level.getBlockState(clickedPos);
        if (placedState.is(targetState.getBlock())) {
            BlockItem.updateCustomBlockEntityTag(level, player, clickedPos, placementStack);
            BlockEntity blockEntity = level.getBlockEntity(clickedPos);
            if (blockEntity != null) {
                blockEntity.applyComponentsFromItemStack(placementStack);
                blockEntity.setChanged();
            }
            placedState.getBlock().setPlacedBy(level, clickedPos, placedState, player, placementStack);
        }

        PlacedBlockTrackerData.get(level).mark(clickedPos);
        RtsPlacementSound.playRemotePlacedBlockAnimation(player, clickedPos);
        RtsPlacementSound.playRemotePlacedBlockSound(player, level, clickedPos);
        RtsPageService.recordRecentItem(session, itemId, S2CRtsStoragePagePayload.RECENT_ITEM_PLACED, 1L);
        RtsPlacementHelper.requestSessionPage(player, session, refreshStoragePage);
        return true;
    }


}
