package com.rtsbuilding.rtsbuilding.client.rendering.overlay;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LevelRenderer;

public final class BoundaryLineRenderer {

    private BoundaryLineRenderer() {
    }

    public static void renderRedBoundary(PoseStack poseStack, VertexConsumer lineBuffer,
            double minX, double minZ, double maxX, double maxZ, double defaultY) {
        float y = (float) defaultY;
        LevelRenderer.renderLineBox(poseStack, lineBuffer, minX, y, minZ, maxX, y + 0.001D, maxZ, 1.0F, 0.25F, 0.25F, 1.0F);
    }
}
