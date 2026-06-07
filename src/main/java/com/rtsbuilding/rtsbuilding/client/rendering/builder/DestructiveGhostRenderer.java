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
 * Renders destructive (range-destroy) ghost previews — per-block coloured
 * outlines with an envelope around non-breakable blocks.
 * <p>
 * During the free-preview phase ({@code readyConfirm == false}),
 * per-block cell boxes are replaced by a merged outer-perimeter skeleton
 * (via {@link UltimineBlockMerger}) rendered with ultimine-style gold
 * breathing colours (inspired by {@link UltimineGhostRenderer}).
 * After the selection is ready ({@code readyConfirm == true}), individual
 * cell boxes are shown for final confirmation.
 */
public final class DestructiveGhostRenderer {

    private static final double BOUNDARY_PADDING = 0.02D;

    // ── Custom no-depth translucent line render type (ultimine-style two-pass) ──

    private static final RenderType LINES_NO_DEPTH = RenderType.create(
            "rtsbuilding_destructive_lines_no_depth",
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

    private DestructiveGhostRenderer() {
    }

    // ===== Public API (called from ShapeGhostRenderer) =====

    /**
     * Renders destructive ghost with per-block cells / merged skeleton and outer envelope.
     */
    static void render(ShapeDataRecords.GhostPreview preview, PoseStack poseStack,
            VertexConsumer lineBuffer, VertexConsumer fillBuffer, float progress, float alphaMultiplier) {
        renderDestructiveGhost(preview, poseStack, lineBuffer, fillBuffer, progress, alphaMultiplier);
    }

    /**
     * Renders the wireframe preview for both build and destructive modes.
     */
    static void renderWireframe(ShapeDataRecords.GhostPreview preview, PoseStack poseStack,
            VertexConsumer lineBuffer, float progress) {
        boolean destructive = preview.destructive();
        boolean readyConfirm = preview.readyConfirm();

        // Envelope for destructive region
        if (destructive && (!RenderingUtil.isEmpty(preview.blocks()) || !RenderingUtil.isEmpty(preview.emptyBlocks()))) {
            float envLineR = RenderingUtil.lerp(1.00F, 0.38F, progress);
            float envLineG = RenderingUtil.lerp(0.86F, 1.00F, progress);
            float envLineB = RenderingUtil.lerp(0.22F, 0.42F, progress);
            renderWireframeEnvelope(poseStack, lineBuffer, preview.blocks(), preview.emptyBlocks(),
                    envLineR, envLineG, envLineB, 0.78F);
        }

        if (preview.blocks() == null || preview.blocks().isEmpty()) {
            return;
        }

        if (destructive && !readyConfirm) {
            // ── Merged skeleton wireframe (ultimine-style gold) for preview phase ──
            List<BlockPos> outerBlocks = filterOuterBlocks(preview.blocks());
            if (!outerBlocks.isEmpty()) {
                List<UltimineBlockMerger.EdgeLine> edges = UltimineBlockMerger.getEdgeLines(outerBlocks);
                if (!edges.isEmpty()) {
                    Matrix4f matrix = poseStack.last().pose();
                    float breathFactor = RenderingUtil.getBreathFactor(0.2F, 0.7F);
                    float r = 1.00F * breathFactor;
                    float g = 0.72F * breathFactor;
                    float b = 0.24F * breathFactor;
                    float edgeR = RenderingUtil.lerp(r, 0.38F, progress);
                    float edgeG = RenderingUtil.lerp(g, 1.00F, progress);
                    float edgeB = RenderingUtil.lerp(b, 0.42F, progress);
                    renderMergedPass1(edges, matrix, lineBuffer, edgeR, edgeG, edgeB);
                    renderMergedPass2(edges, matrix, edgeR, edgeG, edgeB, 0.34F);
                }
            }
        } else if (destructive) {
            // ── Per-block cell line boxes (confirmed) ──
            for (BlockPos pos : preview.blocks()) {
                double cellMinX = pos.getX() + 0.03D;
                double cellMinY = pos.getY() + 0.03D;
                double cellMinZ = pos.getZ() + 0.03D;
                double cellMaxX = pos.getX() + 0.97D;
                double cellMaxY = pos.getY() + 0.97D;
                double cellMaxZ = pos.getZ() + 0.97D;

                DestructiveCellColors dcc = DestructiveCellColors.forConfirmState(false);
                LevelRenderer.renderLineBox(poseStack, lineBuffer,
                        cellMinX, cellMinY, cellMinZ,
                        cellMaxX, cellMaxY, cellMaxZ,
                        dcc.lineR(), dcc.lineG(), dcc.lineB(), dcc.lineA());
            }
        } else {
            // ── Build mode boxes ──
            for (BlockPos pos : preview.blocks()) {
                double cellMinX = pos.getX() + 0.03D;
                double cellMinY = pos.getY() + 0.03D;
                double cellMinZ = pos.getZ() + 0.03D;
                double cellMaxX = pos.getX() + 0.97D;
                double cellMaxY = pos.getY() + 0.97D;
                double cellMaxZ = pos.getZ() + 0.97D;

                float lineR = readyConfirm ? 0.45F : 0.30F;
                float lineG = readyConfirm ? 0.95F : 0.75F;
                float lineB = readyConfirm ? 0.45F : 1.00F;
                LevelRenderer.renderLineBox(poseStack, lineBuffer,
                        cellMinX, cellMinY, cellMinZ,
                        cellMaxX, cellMaxY, cellMaxZ,
                        lineR, lineG, lineB, 0.95F);
            }
        }
    }

    // ===== Destructive ghost rendering =====

    private static void renderDestructiveGhost(ShapeDataRecords.GhostPreview preview, PoseStack poseStack,
            VertexConsumer lineBuffer, VertexConsumer fillBuffer, float progress, float alphaMultiplier) {
        float alpha = RenderingUtil.clamp01(alphaMultiplier);
        if (alpha <= 0.0F) return;

        // Outer envelope (yellow → green transition) — always rendered
        if (!RenderingUtil.isEmpty(preview.blocks()) || !RenderingUtil.isEmpty(preview.emptyBlocks())) {
            float envLineR = RenderingUtil.lerp(1.00F, 0.38F, progress);
            float envLineG = RenderingUtil.lerp(0.86F, 1.00F, progress);
            float envLineB = RenderingUtil.lerp(0.22F, 0.42F, progress);
            float envFillR = RenderingUtil.lerp(1.00F, 0.30F, progress);
            float envFillG = RenderingUtil.lerp(0.86F, 0.95F, progress);
            float envFillB = RenderingUtil.lerp(0.18F, 0.36F, progress);
            renderGhostEnvelope(poseStack, lineBuffer, fillBuffer,
                    preview.blocks(), preview.emptyBlocks(),
                    envLineR, envLineG, envLineB, 0.78F * alpha,
                    envFillR, envFillG, envFillB, 0.10F * alpha);
        }

        List<BlockPos> blocks = preview.blocks();
        if (blocks == null || blocks.isEmpty()) return;

        if (!preview.readyConfirm()) {
            // ── Merged skeleton rendering (ultimine-style gold) for preview phase ──
            renderMergedDestructiveCells(blocks, poseStack, lineBuffer, fillBuffer, progress, alpha);
        } else {
            // ── Per-block cell highlights (confirmed) ──
            DestructiveCellColors dcc = DestructiveCellColors.forConfirmState(true);
            renderPerBlockCells(blocks, poseStack, lineBuffer, fillBuffer, progress, alpha, dcc);
        }
    }

    // ===== Merged skeleton helpers (ultimine-style, for preview state) =====

    /**
     * Renders a merged outer-perimeter skeleton instead of per-block cell boxes.
     * Filters to outer blocks, merges via {@link UltimineBlockMerger}, then
     * renders with a two-pass (opaque + translucent no-depth) approach and
     * breathing gold colours.
     */
    private static void renderMergedDestructiveCells(List<BlockPos> blocks, PoseStack poseStack,
            VertexConsumer lineBuffer, VertexConsumer fillBuffer, float progress, float alpha) {
        List<BlockPos> outerBlocks = filterOuterBlocks(blocks);
        if (outerBlocks.isEmpty()) return;

        List<UltimineBlockMerger.EdgeLine> edges = UltimineBlockMerger.getEdgeLines(outerBlocks);
        if (edges.isEmpty()) return;

        Matrix4f matrix = poseStack.last().pose();

        // Breathing gold colour (ultimine style)
        float breathFactor = RenderingUtil.getBreathFactor(0.2F, 0.7F);
        float r = 1.00F * breathFactor;
        float g = 0.72F * breathFactor;
        float b = 0.24F * breathFactor;
        float edgeR = RenderingUtil.lerp(r, 0.38F, progress);
        float edgeG = RenderingUtil.lerp(g, 1.00F, progress);
        float edgeB = RenderingUtil.lerp(b, 0.42F, progress);

        // Pass 1 — opaque depth-tested edges
        renderMergedPass1(edges, matrix, lineBuffer, edgeR, edgeG, edgeB);

        // Pass 2 — translucent no-depth edges (visible through world geometry)
        renderMergedPass2(edges, matrix, edgeR, edgeG, edgeB, 0.34F);

        // Faint per-block fill
        float fillA = 0.08F * alpha;
        for (BlockPos pos : outerBlocks) {
            LevelRenderer.addChainedFilledBoxVertices(
                    poseStack, fillBuffer,
                    pos.getX(), pos.getY(), pos.getZ(),
                    pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1,
                    edgeR, edgeG, edgeB, fillA);
        }
    }

    /** Opaque depth-tested edge pass (writes to the caller-provided line buffer). */
    private static void renderMergedPass1(List<UltimineBlockMerger.EdgeLine> edges, Matrix4f matrix,
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

    /** Translucent no-depth edge pass (uses internal {@link #LINES_NO_DEPTH}). */
    private static void renderMergedPass2(List<UltimineBlockMerger.EdgeLine> edges, Matrix4f matrix,
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

    // ===== Per-block cell rendering (confirmed state) =====

    private static void renderPerBlockCells(List<BlockPos> blocks, PoseStack poseStack,
            VertexConsumer lineBuffer, VertexConsumer fillBuffer, float progress, float alpha,
            DestructiveCellColors dcc) {
        float lineR = RenderingUtil.lerp(dcc.lineR(), 0.38F, progress) * alpha;
        float lineG = RenderingUtil.lerp(dcc.lineG(), 1.00F, progress);
        float lineB = RenderingUtil.lerp(dcc.lineB(), 0.42F, progress);
        float lineA = dcc.lineA() * alpha;
        float fillR = RenderingUtil.lerp(dcc.fillR(), 0.30F, progress);
        float fillG = RenderingUtil.lerp(dcc.fillG(), 0.95F, progress);
        float fillB = RenderingUtil.lerp(dcc.fillB(), 0.36F, progress);
        float fillA = dcc.fillA() * alpha;

        for (BlockPos pos : blocks) {
            double cellMinX = pos.getX() + 0.03D;
            double cellMinY = pos.getY() + 0.03D;
            double cellMinZ = pos.getZ() + 0.03D;
            double cellMaxX = pos.getX() + 0.97D;
            double cellMaxY = pos.getY() + 0.97D;
            double cellMaxZ = pos.getZ() + 0.97D;

            LevelRenderer.addChainedFilledBoxVertices(
                    poseStack, fillBuffer,
                    cellMinX, cellMinY, cellMinZ,
                    cellMaxX, cellMaxY, cellMaxZ,
                    fillR, fillG, fillB, fillA);

            LevelRenderer.renderLineBox(
                    poseStack, lineBuffer,
                    cellMinX, cellMinY, cellMinZ,
                    cellMaxX, cellMaxY, cellMaxZ,
                    lineR, lineG, lineB, lineA);
        }
    }

    // ===== Envelope rendering =====

    /** Renders a combined bounding-box envelope (line + fill). */
    private static void renderGhostEnvelope(PoseStack poseStack, VertexConsumer lineBuffer, VertexConsumer fillBuffer,
            List<BlockPos> primaryBlocks, List<BlockPos> envelopeBlocks,
            float lineR, float lineG, float lineB, float lineA,
            float fillR, float fillG, float fillB, float fillA) {
        RenderingUtil.Bounds bounds = RenderingUtil.Bounds.from(primaryBlocks, envelopeBlocks);
        if (bounds == null) return;

        double padding = BOUNDARY_PADDING;
        double minX = bounds.minX() - padding;
        double minY = bounds.minY() - padding;
        double minZ = bounds.minZ() - padding;
        double maxX = bounds.maxX() + 1.0D + padding;
        double maxY = bounds.maxY() + 1.0D + padding;
        double maxZ = bounds.maxZ() + 1.0D + padding;

        LevelRenderer.addChainedFilledBoxVertices(poseStack, fillBuffer,
                minX, minY, minZ, maxX, maxY, maxZ,
                fillR, fillG, fillB, fillA);

        LevelRenderer.renderLineBox(poseStack, lineBuffer,
                minX, minY, minZ, maxX, maxY, maxZ,
                lineR, lineG, lineB, lineA);
    }

    private static void renderWireframeEnvelope(PoseStack poseStack, VertexConsumer lineBuffer,
            List<BlockPos> primaryBlocks, List<BlockPos> envelopeBlocks,
            float lineR, float lineG, float lineB, float lineA) {
        RenderingUtil.Bounds bounds = RenderingUtil.Bounds.from(primaryBlocks, envelopeBlocks);
        if (bounds == null) return;
        double padding = BOUNDARY_PADDING;
        LevelRenderer.renderLineBox(poseStack, lineBuffer,
                bounds.minX() - padding, bounds.minY() - padding, bounds.minZ() - padding,
                bounds.maxX() + 1.0D + padding, bounds.maxY() + 1.0D + padding, bounds.maxZ() + 1.0D + padding,
                lineR, lineG, lineB, lineA);
    }

    // ===== Filter helpers =====

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

    // ===== Internal records =====

    /** Cell rendering colours grouped by confirm state (only used for unconfirmed). */
    private record DestructiveCellColors(
            float lineR, float lineG, float lineB, float lineA,
            float fillR, float fillG, float fillB, float fillA) {
        private static DestructiveCellColors forConfirmState(boolean readyConfirm) {
            return new DestructiveCellColors(
                    1.00F,
                    readyConfirm ? 0.95F : 0.46F,
                    readyConfirm ? 0.45F : 0.64F,
                    readyConfirm ? 0.95F : 0.62F,
                    1.00F,
                    readyConfirm ? 0.72F : 0.25F,
                    readyConfirm ? 0.24F : 0.44F,
                    readyConfirm ? 0.22F : 0.07F
            );
        }
    }
}
