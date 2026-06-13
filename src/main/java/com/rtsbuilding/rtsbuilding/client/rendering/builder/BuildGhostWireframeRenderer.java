package com.rtsbuilding.rtsbuilding.client.rendering.builder;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * Wireframe renderer for single-block ghost previews.
 * <p>
 * Renders block outline wireframes. Colour depends on confirmation state:
 * <ul>
 *   <li>Unconfirmed (preview): cyan wireframe</li>
 *   <li>Ready (confirmed): green wireframe</li>
 * </ul>
 */
public final class BuildGhostWireframeRenderer {

    private BuildGhostWireframeRenderer() {
    }

    /**
     * Renders wireframes at all target positions.
     *
     * @param blocks       Target block positions
     * @param poseStack    Pose stack for coordinate transforms
     * @param lineBuffer   Line vertex buffer
     * @param readyConfirm Whether the placement is ready to confirm
     */
    public static void renderWireframes(List<BlockPos> blocks, PoseStack poseStack,
            VertexConsumer lineBuffer, boolean readyConfirm) {
        if (blocks == null || blocks.isEmpty()) {
            return;
        }
        float lineR = readyConfirm ? 0.45F : 0.30F;
        float lineG = readyConfirm ? 0.95F : 0.75F;
        float lineB = readyConfirm ? 0.45F : 1.00F;

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
