package com.rtsbuilding.rtsbuilding.client.rendering.util;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;

/**
 * Ray-casting utility class.
 * Provides mouse-cursor ray calculation and block/entity hit detection.
 */
public final class RaycastHelper {

    /**
     * Private constructor to prevent instantiation.
     */
    private RaycastHelper() {
    }

    /**
     * Casts a ray from the camera towards the mouse cursor and detects the hit block.
     *
     * @param minecraft          Minecraft client instance
     * @param camPos             Camera origin position
     * @param to                 Ray end position
     * @param includeFluidSource Whether to include fluid source blocks
     * @return Block hit result, or null if nothing was hit
     */
    public static BlockHitResult raycastBlockFromCursor(Minecraft minecraft, Vec3 camPos, Vec3 to,
            boolean includeFluidSource) {
        ClipContext.Fluid fluidMode = includeFluidSource ? ClipContext.Fluid.SOURCE_ONLY : ClipContext.Fluid.NONE;
        HitResult hit = null;
        if (minecraft.getCameraEntity() != null) {
            if (minecraft.level != null) {
                hit = minecraft.level.clip(new ClipContext(camPos, to, ClipContext.Block.OUTLINE, fluidMode,
                        minecraft.getCameraEntity()));
            }
        }
        if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
            return bhr;
        }
        return null;
    }

    /**
     * Casts a ray from the camera towards the mouse cursor and detects the hit entity.
     *
     * @param minecraft Minecraft client instance
     * @param camPos    Camera origin position
     * @param to        Ray end position
     * @param viewDir   View direction vector
     * @param reach     Maximum ray distance
     * @return Entity hit result, or null if nothing was hit
     */
    public static EntityHitResult raycastEntityFromCursor(Minecraft minecraft, Vec3 camPos, Vec3 to, Vec3 viewDir,
            double reach) {
        Entity cameraEntity = minecraft.getCameraEntity();
        if (cameraEntity == null) {
            return null;
        }

        // Build search AABB: expand from camera along view direction
        AABB search = cameraEntity.getBoundingBox().expandTowards(viewDir.scale(reach)).inflate(1.0D);

        // Perform entity ray-cast
        return ProjectileUtil.getEntityHitResult(
                cameraEntity,
                camPos,
                to,
                search,
                entity -> entity != null
                        && entity.isAlive()
                        && entity.isPickable()
                        && entity != cameraEntity
                        && entity != minecraft.player,
                reach * reach);
    }

    /**
     * Computes the ray direction vector corresponding to the mouse cursor.
     * Accounts for FOV, window dimensions, camera orientation, etc.
     *
     * @param minecraft Minecraft client instance
     * @return Normalised ray direction vector
     */
    public static Vec3 computeCursorRayDirection(Minecraft minecraft) {
        // Get mouse screen coordinates
        double mouseX = minecraft.mouseHandler.xpos();
        double mouseY = minecraft.mouseHandler.ypos();
        double width = Math.max(1.0D, minecraft.getWindow().getScreenWidth());
        double height = Math.max(1.0D, minecraft.getWindow().getScreenHeight());

        // Convert to NDC (Normalised Device Coordinates), range [-1, 1]
        double nx = (mouseX / width) * 2.0D - 1.0D;
        double ny = 1.0D - (mouseY / height) * 2.0D;

        // Get camera facing angles
        float yawDeg = minecraft.gameRenderer.getMainCamera().getYRot();
        float pitchDeg = minecraft.gameRenderer.getMainCamera().getXRot();
        double yaw = Math.toRadians(yawDeg);
        double pitch = Math.toRadians(pitchDeg);

        // Compute forward vector (camera's facing direction)
        Vec3 forward = new Vec3(
                -Math.sin(yaw) * Math.cos(pitch),
                -Math.sin(pitch),
                Math.cos(yaw) * Math.cos(pitch)).normalize();

        // Compute right vector
        Vec3 right = new Vec3(Math.cos(yaw), 0.0D, Math.sin(yaw)).normalize();

        // Compute up vector (cross product)
        Vec3 up = forward.cross(right).normalize();

        // Compute FOV-dependent scale factor
        double fovY = Math.toRadians(minecraft.options.fov().get());
        double tanY = Math.tan(fovY * 0.5D);
        double tanX = tanY * (width / height);

        // Combine final ray direction: forward + horizontal offset + vertical offset
        // Note: the yaw basis produces a left vector, so negate X NDC to keep screen-right matching ray-right
        return forward.add(right.scale(-nx * tanX)).add(up.scale(ny * tanY)).normalize();
    }
}
