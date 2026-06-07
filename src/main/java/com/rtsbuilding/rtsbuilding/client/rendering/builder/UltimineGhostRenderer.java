package com.rtsbuilding.rtsbuilding.client.rendering.builder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.core.BlockPos;

import org.joml.Matrix4f;

import com.rtsbuilding.rtsbuilding.client.rendering.util.RenderingUtil;
import com.rtsbuilding.rtsbuilding.client.screen.shape.ShapeDataRecords;

/**
 * Renders the chain-mining (ultimine) ghost preview in FTB Ultimine style,
 * and provides shared two-pass edge rendering primitives used by
 * {@link MergedSkeletonRenderer}.
 */
public final class UltimineGhostRenderer {

    // ── Custom no-depth translucent line render type ──

    private static final RenderType LINES_NO_DEPTH = RenderType.create(
            "rtsbuilding_ultimine_lines_no_depth",
            DefaultVertexFormat.POSITION_COLOR_NORMAL,
            VertexFormat.Mode.LINES,
            512,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.RENDERTYPE_LINES_SHADER)
                    .setLineState(RenderStateShard.DEFAULT_LINE)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
                    .setOutputState(RenderStateShard.MAIN_TARGET)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .setCullState(RenderStateShard.NO_CULL)
                    .createCompositeState(false));

    private static final ByteBufferBuilder LINES_NO_DEPTH_BACKING = new ByteBufferBuilder(LINES_NO_DEPTH.bufferSize());

    // ── Breathing colour parameters ──

    private static final float BREATH_SPEED = 0.2F;
    private static final float BREATH_MIN_FACTOR = 0.7F;

    private UltimineGhostRenderer() {
    }

    // ===== Public API (called from ShapeGhostRenderer) =====

    static void render(ShapeDataRecords.GhostPreview preview, PoseStack poseStack,
            VertexConsumer lineBuffer, VertexConsumer fillBuffer, float progress) {
        List<BlockPos> blocks = preview.blocks();
        if (blocks == null || blocks.isEmpty()) return;

        // Step 1 — outer perimeter filtering
        List<BlockPos> outerBlocks = filterOuterBlocks(blocks);
        if (outerBlocks.isEmpty()) return;

        // Step 2 — merge adjacent outer blocks and extract edge segments
        List<UltimineBlockMerger.EdgeLine> edges = UltimineBlockMerger.getEdgeLines(outerBlocks);
        if (edges.isEmpty()) return;

        Matrix4f matrix = poseStack.last().pose();

        // Step 3 — breathing gold colour
        float breathFactor = RenderingUtil.getBreathFactor(BREATH_SPEED, BREATH_MIN_FACTOR);
        float r = 1.00F * breathFactor;
        float g = 0.72F * breathFactor;
        float b = 0.24F * breathFactor;
        float edgeR = RenderingUtil.lerp(r, 0.38F, progress);
        float edgeG = RenderingUtil.lerp(g, 1.00F, progress);
        float edgeB = RenderingUtil.lerp(b, 0.42F, progress);

        // Step 4 — opaque depth-tested edges
        renderPass1(edges, matrix, lineBuffer, edgeR, edgeG, edgeB);

        // Step 5 — translucent no-depth edges
        renderPass2(edges, matrix, edgeR, edgeG, edgeB, 0.34F);

        // Step 6 — faint per-block fill
        renderFill(outerBlocks, poseStack, fillBuffer, edgeR, edgeG, edgeB, 0.08F);
    }

    // ===== Shared edge rendering primitives (used by MergedSkeletonRenderer) =====

    static void renderPass1(List<UltimineBlockMerger.EdgeLine> edges, Matrix4f matrix,
            VertexConsumer lineBuffer, float r, float g, float b) {
        for (UltimineBlockMerger.EdgeLine edge : edges) {
            lineBuffer.addVertex(matrix, (float) edge.x1(), (float) edge.y1(), (float) edge.z1())
                    .setColor(r, g, b, 0.95F)
                    .setNormal(edge.xn(), edge.yn(), edge.zn());
            lineBuffer.addVertex(matrix, (float) edge.x2(), (float) edge.y2(), (float) edge.z2())
                    .setColor(r, g, b, 0.95F)
                    .setNormal(edge.xn(), edge.yn(), edge.zn());
        }
    }

    static void renderPass2(List<UltimineBlockMerger.EdgeLine> edges, Matrix4f matrix,
            float r, float g, float b, float alpha) {
        BufferBuilder ndBuffer = new BufferBuilder(LINES_NO_DEPTH_BACKING, VertexFormat.Mode.LINES,
                DefaultVertexFormat.POSITION_COLOR_NORMAL);
        for (UltimineBlockMerger.EdgeLine edge : edges) {
            ndBuffer.addVertex(matrix, (float) edge.x1(), (float) edge.y1(), (float) edge.z1())
                    .setColor(r, g, b, alpha)
                    .setNormal(edge.xn(), edge.yn(), edge.zn());
            ndBuffer.addVertex(matrix, (float) edge.x2(), (float) edge.y2(), (float) edge.z2())
                    .setColor(r, g, b, alpha)
                    .setNormal(edge.xn(), edge.yn(), edge.zn());
        }
        var meshData = ndBuffer.build();
        if (meshData != null) {
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            LINES_NO_DEPTH.draw(meshData);
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
        }
    }

    static void renderFill(List<BlockPos> outerBlocks, PoseStack poseStack,
            VertexConsumer fillBuffer, float r, float g, float b, float fillA) {
        for (BlockPos pos : outerBlocks) {
            LevelRenderer.addChainedFilledBoxVertices(
                    poseStack, fillBuffer,
                    pos.getX(), pos.getY(), pos.getZ(),
                    pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1,
                    r, g, b, fillA);
        }
    }

    // ===== Private helpers =====

    /**
     * Filters to outer-perimeter blocks (at least one face neighbour outside
     * the selection set).
     */
    private static List<BlockPos> filterOuterBlocks(List<BlockPos> blocks) {
        Set<BlockPos> allBlocks = new HashSet<>(blocks);
        BlockPos[] faceOffsets = {
                new BlockPos(1, 0, 0), new BlockPos(-1, 0, 0),
                new BlockPos(0, 1, 0), new BlockPos(0, -1, 0),
                new BlockPos(0, 0, 1), new BlockPos(0, 0, -1)
        };
        List<BlockPos> outerBlocks = new ArrayList<>();
        for (BlockPos pos : blocks) {
            boolean isOuter = false;
            for (BlockPos offset : faceOffsets) {
                if (!allBlocks.contains(pos.offset(offset))) {
                    isOuter = true;
                    break;
                }
            }
            if (isOuter) outerBlocks.add(pos);
        }
        return outerBlocks;
    }
}
