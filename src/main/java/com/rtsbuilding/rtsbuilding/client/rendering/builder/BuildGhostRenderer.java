package com.rtsbuilding.rtsbuilding.client.rendering.builder;

import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.rtsbuilding.rtsbuilding.client.controller.ClientRtsController;
import com.rtsbuilding.rtsbuilding.client.rendering.util.GhostAlphaBufferSource;
import com.rtsbuilding.rtsbuilding.client.rendering.util.RaycastHelper;
import com.rtsbuilding.rtsbuilding.client.screen.shape.ShapeDataRecords;

import net.minecraft.client.Camera;
import net.minecraft.util.Mth;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Renders build-mode preview layers. The translucent block model and the
 * wireframe outline are intentionally independent so player settings can show
 * ghost-only, wireframe-only, both, or neither.
 */
public final class BuildGhostRenderer {
    static final float BUILD_GHOST_ALPHA = 0.8F;

    private BuildGhostRenderer() {
    }

    static void render(Minecraft minecraft, ShapeDataRecords.GhostPreview preview,
            PoseStack poseStack, VertexConsumer lineBuffer, VertexConsumer fillBuffer,
            boolean renderBlockGhost, boolean renderWireframe) {
        if (preview == null || (!renderBlockGhost && !renderWireframe)) {
            return;
        }
        BlockPos targetPos = preview.blocks().isEmpty() ? null : preview.blocks().get(0);
        BlockState blockState = resolveBuildBlockState(minecraft, targetPos);
        if (renderBlockGhost) {
            if (blockState != null && !blockState.isAir() && blockState.getRenderShape() == RenderShape.MODEL) {
                renderBuildGhostModels(minecraft, preview, poseStack, blockState);
            } else {
                renderBuildFallbackFill(preview, poseStack, fillBuffer);
            }
        }
        if (renderWireframe) {
            renderBuildWireframes(preview, poseStack, lineBuffer);
        }
    }

    private static BlockState resolveBuildBlockState(Minecraft minecraft, BlockPos targetPos) {
        ClientRtsController controller = ClientRtsController.get();

        // Determine the source item stack
        ItemStack itemStack = resolveGhostItemStack(minecraft, controller);
        if (itemStack == null || !(itemStack.getItem() instanceof BlockItem blockItem)) {
            return null;
        }

        // If no target position, use default state
        if (targetPos == null) {
            return blockItem.getBlock().defaultBlockState();
        }

        // Resolve block state using target-to-camera direction (like server does via
        // Block.getStateForPlacement), then apply rotation
        BlockState state = resolveStateWithCamera(minecraft, blockItem, itemStack, targetPos);
        if (state == null) return null;

        int rotateDegrees = controller.getPlaceRotateDegrees();
        if (rotateDegrees != 0) {
            state = applyRotation(state, rotateDegrees);
        }
        return state;
    }

    private static ItemStack resolveGhostItemStack(Minecraft minecraft, ClientRtsController controller) {
        ItemStack itemPreview = controller.getSelectedItemPreview();
        if (!itemPreview.isEmpty() && itemPreview.getItem() instanceof BlockItem) {
            return itemPreview;
        }
        if (minecraft != null && minecraft.player != null) {
            ItemStack mainHand = minecraft.player.getMainHandItem();
            if (mainHand.getItem() instanceof BlockItem) {
                return mainHand;
            }
        }
        return null;
    }

    /**
     * Simulates {@link BlockItem#getBlock()}.{@link
     * net.minecraft.world.level.block.Block#getStateForPlacement(BlockPlaceContext)
     * getStateForPlacement} using the client camera's yaw/pitch so that the ghost
     * preview matches the server-placed block state.
     *
     * <p>Uses an anonymous {@link BlockPlaceContext} subclass that overrides the
     * direction methods to return values computed from the target→camera vector,
     * avoiding any mutation of the player entity. The target position is passed
     * through so neighbor-dependent blocks (fences, walls, etc.) see correct
     * context.
     */
    private static BlockState resolveStateWithCamera(Minecraft minecraft, BlockItem blockItem, ItemStack stack, BlockPos targetPos) {
        if (minecraft == null || minecraft.player == null || minecraft.level == null) return null;

        Camera camera = minecraft.gameRenderer.getMainCamera();
        Vec3 cameraPos = camera.getPosition();
        Vec3 targetCenter = Vec3.atCenterOf(targetPos);

        // Compute the direction the camera is looking (camera → target).
        double dx = targetCenter.x - cameraPos.x;
        double dy = targetCenter.y - cameraPos.y;
        double dz = targetCenter.z - cameraPos.z;
        float yawDeg = (float) Math.toDegrees(Mth.atan2(-dx, dz));

        // Use the actual cursor ray direction (matching ScreenCursorPicker's ray mechanism).
        Vec3 viewDir = RaycastHelper.computeCursorRayDirection(minecraft);

        // ── Perform an actual block raycast to get the precise clicked face and hit location ──
        // This matches what ScreenCursorPicker.pickBlockHit() returns and avoids snapping
        // to cardinal axes with Direction.getNearest(), which gives wrong faces when aiming
        // at wall blocks from an angle.
        Vec3 rayEnd = cameraPos.add(viewDir.scale(128.0D));
        BlockHitResult actualHit = RaycastHelper.raycastBlockFromCursor(
                minecraft, cameraPos, rayEnd, false);

        Direction clickedFace;
        BlockPos adjacentPos;
        Vec3 hitLocation;

        if (actualHit != null) {
            // Use the actual raycast result: real clicked face, clicked block, and exact
            // intersection point.
            clickedFace = actualHit.getDirection();
            adjacentPos = actualHit.getBlockPos();
            hitLocation = actualHit.getLocation();
        } else {
            // Fallback when no block is hit: compute from ray direction via ray-plane
            // intersection on the adjacent block's face.
            clickedFace = Direction.getNearest(-viewDir.x, -viewDir.y, -viewDir.z);
            adjacentPos = targetPos.relative(clickedFace.getOpposite());
            switch (clickedFace) {
                case DOWN -> {
                    double planeY = adjacentPos.getY();
                    if (viewDir.y != 0.0) {
                        double t = (planeY - cameraPos.y) / viewDir.y;
                        hitLocation = new Vec3(cameraPos.x + t * viewDir.x, planeY, cameraPos.z + t * viewDir.z);
                    } else {
                        hitLocation = new Vec3(targetCenter.x, planeY, targetCenter.z);
                    }
                }
                case UP -> {
                    double planeY = adjacentPos.getY() + 1.0;
                    if (viewDir.y != 0.0) {
                        double t = (planeY - cameraPos.y) / viewDir.y;
                        hitLocation = new Vec3(cameraPos.x + t * viewDir.x, planeY, cameraPos.z + t * viewDir.z);
                    } else {
                        hitLocation = new Vec3(targetCenter.x, planeY, targetCenter.z);
                    }
                }
                case NORTH -> {
                    double planeZ = adjacentPos.getZ();
                    if (viewDir.z != 0.0) {
                        double t = (planeZ - cameraPos.z) / viewDir.z;
                        hitLocation = new Vec3(cameraPos.x + t * viewDir.x, cameraPos.y + t * viewDir.y, planeZ);
                    } else {
                        hitLocation = new Vec3(targetCenter.x, targetCenter.y, planeZ);
                    }
                }
                case SOUTH -> {
                    double planeZ = adjacentPos.getZ() + 1.0;
                    if (viewDir.z != 0.0) {
                        double t = (planeZ - cameraPos.z) / viewDir.z;
                        hitLocation = new Vec3(cameraPos.x + t * viewDir.x, cameraPos.y + t * viewDir.y, planeZ);
                    } else {
                        hitLocation = new Vec3(targetCenter.x, targetCenter.y, planeZ);
                    }
                }
                case WEST -> {
                    double planeX = adjacentPos.getX();
                    if (viewDir.x != 0.0) {
                        double t = (planeX - cameraPos.x) / viewDir.x;
                        hitLocation = new Vec3(planeX, cameraPos.y + t * viewDir.y, cameraPos.z + t * viewDir.z);
                    } else {
                        hitLocation = new Vec3(planeX, targetCenter.y, targetCenter.z);
                    }
                }
                case EAST -> {
                    double planeX = adjacentPos.getX() + 1.0;
                    if (viewDir.x != 0.0) {
                        double t = (planeX - cameraPos.x) / viewDir.x;
                        hitLocation = new Vec3(planeX, cameraPos.y + t * viewDir.y, cameraPos.z + t * viewDir.z);
                    } else {
                        hitLocation = new Vec3(planeX, targetCenter.y, targetCenter.z);
                    }
                }
                default -> hitLocation = targetCenter;
            }
        }

        // Create a context using the computed face and hit location, with blockPos
        // set to the placement position (post-BlockItem.useOn adjustment).
        BlockPlaceContext context = new BlockPlaceContext(
                minecraft.level,
                minecraft.player,
                InteractionHand.MAIN_HAND,
                stack,
                new BlockHitResult(hitLocation, clickedFace, adjacentPos, false)) {
            @Override
            public Direction getHorizontalDirection() {
                return Direction.fromYRot(yawDeg);
            }

            @Override
            public Direction getNearestLookingDirection() {
                return clickedFace;
            }

            @Override
            public Direction getNearestLookingVerticalDirection() {
                return Direction.getNearest(0.0, dy, 0.0);
            }

            @Override
            public float getRotation() {
                return yawDeg;
            }
        };
        return blockItem.getBlock().getStateForPlacement(context);
    }

    private static BlockState applyRotation(BlockState state, int rotateDegrees) {
        int turns = (rotateDegrees / 90) & 3;
        if (turns == 0) return state;
        BlockState rotated = state;
        for (int i = 0; i < turns; i++) {
            rotated = rotated.rotate(Rotation.CLOCKWISE_90);
        }
        return rotated;
    }

    private static void renderBuildGhostModels(Minecraft minecraft, ShapeDataRecords.GhostPreview preview,
            PoseStack poseStack, BlockState blockState) {
        List<BlockPos> blocks = preview.blocks();
        if (minecraft == null || blocks.isEmpty()) {
            return;
        }
        MultiBufferSource.BufferSource blockBuffer = minecraft.renderBuffers().bufferSource();
        MultiBufferSource translucentBuffer = new GhostAlphaBufferSource(blockBuffer, BUILD_GHOST_ALPHA);

        for (BlockPos pos : blocks) {
            renderGhostAt(minecraft, pos, blockState, poseStack, translucentBuffer);
            // Expand multi-block parts for this position
            expandMultiblockGhost(minecraft, pos, blockState, poseStack, translucentBuffer);
        }
        blockBuffer.endBatch();
    }

    /**
     * Renders a single translucent ghost block model at the given position.
     */
    private static void renderGhostAt(Minecraft minecraft, BlockPos pos, BlockState state,
            PoseStack poseStack, MultiBufferSource translucentBuffer) {
        if (state.isAir() || state.getRenderShape() != RenderShape.MODEL) return;
        int light = minecraft.level == null ? 0xF000F0 : LevelRenderer.getLightColor(minecraft.level, pos);
        poseStack.pushPose();
        poseStack.translate(pos.getX(), pos.getY(), pos.getZ());
        minecraft.getBlockRenderer().renderSingleBlock(
                state, poseStack, translucentBuffer,
                light, OverlayTexture.NO_OVERLAY);
        poseStack.popPose();
    }

    /**
     * Detects and renders additional ghost positions for multi-block blocks
     * (doors, tall plants, beds, and modded equivalents) by checking common
     * {@link net.minecraft.world.level.block.state.properties.Property block-state
     * properties} that indicate multi-block occupancy.
     * <p>
     * Currently handles:
     * <ul>
     *   <li>{@link BlockStateProperties#DOUBLE_BLOCK_HALF} — vertical double blocks
     *       ({@code LOWER → pos.above()}, {@code UPPER → pos.below()})</li>
     *   <li>{@link BlockStateProperties#BED_PART} — horizontal beds
     *       ({@code FOOT → pos.relative(facing)}, {@code HEAD → pos.relative(facing.getOpposite())})</li>
     * </ul>
     * Modded blocks that reuse these standard properties are automatically supported.
     */
    private static void expandMultiblockGhost(Minecraft minecraft, BlockPos pos, BlockState state,
            PoseStack poseStack, MultiBufferSource translucentBuffer) {
        // Vertical double-blocks (doors, tall plants, etc.)
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            DoubleBlockHalf half = state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF);
            if (half == DoubleBlockHalf.LOWER) {
                renderGhostAt(minecraft, pos.above(),
                        state.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER),
                        poseStack, translucentBuffer);
            } else if (half == DoubleBlockHalf.UPPER) {
                renderGhostAt(minecraft, pos.below(),
                        state.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER),
                        poseStack, translucentBuffer);
            }
        }

        // Beds (horizontal multi-block, extends in facing direction)
        if (state.hasProperty(BlockStateProperties.BED_PART)
                && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            BedPart part = state.getValue(BlockStateProperties.BED_PART);
            Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            if (part == BedPart.FOOT) {
                renderGhostAt(minecraft, pos.relative(facing),
                        state.setValue(BlockStateProperties.BED_PART, BedPart.HEAD),
                        poseStack, translucentBuffer);
            } else if (part == BedPart.HEAD) {
                renderGhostAt(minecraft, pos.relative(facing.getOpposite()),
                        state.setValue(BlockStateProperties.BED_PART, BedPart.FOOT),
                        poseStack, translucentBuffer);
            }
        }
    }

    private static void renderBuildFallbackFill(ShapeDataRecords.GhostPreview preview, PoseStack poseStack,
            VertexConsumer fillBuffer) {
        List<BlockPos> blocks = preview.blocks();
        if (blocks.isEmpty()) {
            return;
        }
        float fillR = preview.readyConfirm() ? 0.24F : 0.16F;
        float fillG = preview.readyConfirm() ? 0.72F : 0.55F;
        float fillB = preview.readyConfirm() ? 0.24F : 0.90F;
        float fillA = preview.readyConfirm() ? 0.22F : 0.16F;

        for (BlockPos pos : blocks) {
            double minX = pos.getX() + 0.03D;
            double minY = pos.getY() + 0.03D;
            double minZ = pos.getZ() + 0.03D;
            double maxX = pos.getX() + 0.97D;
            double maxY = pos.getY() + 0.97D;
            double maxZ = pos.getZ() + 0.97D;
            LevelRenderer.addChainedFilledBoxVertices(
                    poseStack, fillBuffer,
                    minX, minY, minZ,
                    maxX, maxY, maxZ,
                    fillR, fillG, fillB, fillA);
        }
    }

    private static void renderBuildWireframes(ShapeDataRecords.GhostPreview preview, PoseStack poseStack,
            VertexConsumer lineBuffer) {
        List<BlockPos> blocks = preview.blocks();
        if (blocks.isEmpty()) {
            return;
        }
        float lineR = preview.readyConfirm() ? 0.45F : 0.30F;
        float lineG = preview.readyConfirm() ? 0.95F : 0.75F;
        float lineB = preview.readyConfirm() ? 0.45F : 1.00F;

        for (BlockPos pos : blocks) {
            double minX = pos.getX() + 0.03D;
            double minY = pos.getY() + 0.03D;
            double minZ = pos.getZ() + 0.03D;
            double maxX = pos.getX() + 0.97D;
            double maxY = pos.getY() + 0.97D;
            double maxZ = pos.getZ() + 0.97D;
            LevelRenderer.renderLineBox(
                    poseStack, lineBuffer,
                    minX, minY, minZ,
                    maxX, maxY, maxZ,
                    lineR, lineG, lineB, 0.95F);
        }
    }
}
