package com.rtsbuilding.rtsbuilding.client.rendering.builder;

import com.mojang.blaze3d.vertex.PoseStack;
import com.rtsbuilding.rtsbuilding.client.rendering.util.GhostAlphaBufferSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.List;

/**
 * Block model renderer for single-block placement ghosts.
 * <p>
 * Renders translucent block models and automatically handles multi-block
 * expansions (doors, tall plants, beds, etc.).
 */
public final class BuildGhostModelRenderer {

    /** Ghost model opacity */
    public static final float GHOST_ALPHA = 0.8F;

    private BuildGhostModelRenderer() {
    }

    /**
     * Renders translucent block models at all target positions.
     *
     * @param minecraft    Minecraft client instance
     * @param blocks       Target block position list
     * @param poseStack    Pose stack
     * @param blockState   BlockState to render
     */
    public static void renderModels(Minecraft minecraft, List<BlockPos> blocks,
            PoseStack poseStack, BlockState blockState) {
        if (minecraft == null || blocks == null || blocks.isEmpty()) {
            return;
        }
        MultiBufferSource.BufferSource blockBuffer = minecraft.renderBuffers().bufferSource();
        MultiBufferSource translucentBuffer = new GhostAlphaBufferSource(blockBuffer, GHOST_ALPHA);

        for (BlockPos pos : blocks) {
            renderGhostAt(minecraft, pos, blockState, poseStack, translucentBuffer);
            expandMultiblockGhost(minecraft, pos, blockState, poseStack, translucentBuffer);
        }
        blockBuffer.endBatch();
    }

    /**
     * Renders a translucent block model at a single position.
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
     * Detects and renders additional ghost parts for multi-block structures
     * (doors, tall plants, beds, etc.) via standard BlockState properties.
     */
    private static void expandMultiblockGhost(Minecraft minecraft, BlockPos pos, BlockState state,
            PoseStack poseStack, MultiBufferSource translucentBuffer) {
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
}
