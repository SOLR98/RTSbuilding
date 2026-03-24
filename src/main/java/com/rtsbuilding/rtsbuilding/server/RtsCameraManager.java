package com.rtsbuilding.rtsbuilding.server;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.rtsbuilding.rtsbuilding.RtsbuildingMod;
import com.rtsbuilding.rtsbuilding.entity.RtsCameraEntity;
import com.rtsbuilding.rtsbuilding.network.S2CRtsCameraStatePayload;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

public final class RtsCameraManager {
    private static final double MAX_RADIUS = 48.0D; // 3 chunks
    private static final double MIN_HEIGHT = -5.0D;
    private static final double MAX_HEIGHT = 80.0D;
    private static final double MIN_DIST = 8.0D;
    private static final double MAX_DIST = 72.0D;
    private static final float MIN_PITCH = -90.0F;
    private static final float MAX_PITCH = 90.0F;

    private static final float ROT_INPUT_CLAMP = 20.0F;
    private static final float ROTATE_GAIN_X = 0.24F;
    private static final float ROTATE_GAIN_Y = 0.22F;
    private static final double DOLLY_PER_SCROLL = 2.6D;

    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();

    private RtsCameraManager() {
    }

    public static void toggle(ServerPlayer player) {
        if (SESSIONS.containsKey(player.getUUID())) {
            stop(player);
        } else {
            start(player);
        }
    }

    public static void start(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        Vec3 anchor = player.position();

        float yaw = snapQuarter(player.getYRot());
        float pitch = 70.0F;

        RtsCameraEntity camera = new RtsCameraEntity(RtsbuildingMod.RTS_CAMERA_ENTITY.get(), level);
        camera.snapTo(anchor.x, anchor.y + 18.0D, anchor.z, yaw, pitch);
        level.addFreshEntity(camera);

        Session session = new Session(camera.getUUID(), anchor, yaw, pitch, camera.getY() - anchor.y);
        SESSIONS.put(player.getUUID(), session);
        RtsStorageManager.onRtsEnabled(player);

        PacketDistributor.sendToPlayer(player, new S2CRtsCameraStatePayload(
                true,
                camera.getId(),
                anchor.x,
                anchor.y,
                anchor.z,
                MAX_RADIUS,
                session.heightOffset(),
                session.yawDeg(),
                session.pitchDeg()));
    }

    public static void stop(ServerPlayer player) {
        Session session = SESSIONS.remove(player.getUUID());
        if (session != null) {
            Entity entity = player.serverLevel().getEntity(session.cameraUuid());
            if (entity != null) {
                entity.discard();
            }
        }

        PacketDistributor.sendToPlayer(player, new S2CRtsCameraStatePayload(false, -1, 0.0D, 0.0D, 0.0D, MAX_RADIUS, 18.0D, 0.0F, 70.0F));
        RtsStorageManager.onRtsDisabled(player);
    }

    public static void stopIfActive(ServerPlayer player) {
        if (SESSIONS.containsKey(player.getUUID())) {
            stop(player);
        }
    }

    public static boolean isActive(ServerPlayer player) {
        return SESSIONS.containsKey(player.getUUID());
    }

    public static boolean isWithinActionRadius(ServerPlayer player, BlockPos pos) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null || pos == null) {
            return false;
        }

        double dx = (pos.getX() + 0.5D) - session.anchor().x;
        double dz = (pos.getZ() + 0.5D) - session.anchor().z;
        return (dx * dx + dz * dz) <= (MAX_RADIUS * MAX_RADIUS);
    }

    public static void move(ServerPlayer player, float forward, float strafe, float panX, float panY, float rotateX,
            float rotateY, float scroll, int rotateSteps, boolean fast) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null) {
            return;
        }

        Entity baseEntity = player.serverLevel().getEntity(session.cameraUuid());
        if (!(baseEntity instanceof RtsCameraEntity camera)) {
            stop(player);
            return;
        }

        float safeRotateX = Mth.clamp(rotateX, -ROT_INPUT_CLAMP, ROT_INPUT_CLAMP);
        float safeRotateY = Mth.clamp(rotateY, -ROT_INPUT_CLAMP, ROT_INPUT_CLAMP);

        float yaw = session.yawDeg() + (safeRotateX * ROTATE_GAIN_X);
        if (rotateSteps != 0) {
            yaw = snapQuarter(yaw + (90.0F * rotateSteps));
        }

        float pitch = Mth.clamp(session.pitchDeg() + (safeRotateY * ROTATE_GAIN_Y), MIN_PITCH, MAX_PITCH);

        double speed = fast ? 0.80D : 0.45D;

        double yawRad = Math.toRadians(yaw);
        double sin = Math.sin(yawRad);
        double cos = Math.cos(yawRad);

        double targetX = camera.getX();
        double targetY = camera.getY();
        double targetZ = camera.getZ();

        double dx = (-sin * forward + cos * strafe) * speed;
        double dz = (cos * forward + sin * strafe) * speed;

        double dragScale = 0.020D * Math.max(8.0D, session.heightOffset());
        double moveRight = panX * dragScale;
        double moveForward = -panY * dragScale;

        double rightX = Math.cos(yawRad);
        double rightZ = Math.sin(yawRad);
        double fwdX = -Math.sin(yawRad);
        double fwdZ = Math.cos(yawRad);

        dx += rightX * moveRight + fwdX * moveForward;
        dz += rightZ * moveRight + fwdZ * moveForward;

        targetX += dx;
        targetZ += dz;

        // Dolly zoom along current look direction (not mechanical Y-only zoom).
        if (scroll != 0.0F) {
            double pitchRad = Math.toRadians(pitch);
            double lookX = -Math.sin(yawRad) * Math.cos(pitchRad);
            double lookY = -Math.sin(pitchRad);
            double lookZ = Math.cos(yawRad) * Math.cos(pitchRad);

            double dolly = scroll * DOLLY_PER_SCROLL;
            targetX += lookX * dolly;
            targetY += lookY * dolly;
            targetZ += lookZ * dolly;
        }

        double adx = targetX - session.anchor().x;
        double adz = targetZ - session.anchor().z;
        double distSqr = adx * adx + adz * adz;
        double maxSqr = MAX_RADIUS * MAX_RADIUS;
        if (distSqr > maxSqr) {
            double dist = Math.sqrt(distSqr);
            double scale = MAX_RADIUS / dist;
            targetX = session.anchor().x + (adx * scale);
            targetZ = session.anchor().z + (adz * scale);
        }

        targetY = Mth.clamp(targetY, session.anchor().y + MIN_HEIGHT, session.anchor().y + MAX_HEIGHT);

        Vec3 toCam = new Vec3(targetX - session.anchor().x, targetY - session.anchor().y, targetZ - session.anchor().z);
        double dist = toCam.length();
        if (dist > 1.0e-6) {
            double clamped = Mth.clamp(dist, MIN_DIST, MAX_DIST);
            if (Math.abs(clamped - dist) > 1.0e-4) {
                Vec3 n = toCam.scale(clamped / dist);
                targetX = session.anchor().x + n.x;
                targetY = session.anchor().y + n.y;
                targetZ = session.anchor().z + n.z;
            }
        }

        targetY = Mth.clamp(targetY, session.anchor().y + MIN_HEIGHT, session.anchor().y + MAX_HEIGHT);

        camera.snapTo(targetX, targetY, targetZ, yaw, pitch);

        SESSIONS.put(player.getUUID(), new Session(session.cameraUuid(), session.anchor(), yaw, pitch, targetY - session.anchor().y));
    }

    private static float snapQuarter(float yaw) {
        int quarter = Math.round(yaw / 90.0F);
        return quarter * 90.0F;
    }

    private record Session(UUID cameraUuid, Vec3 anchor, float yawDeg, float pitchDeg, double heightOffset) {
    }
}

