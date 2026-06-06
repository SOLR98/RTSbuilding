package com.rtsbuilding.rtsbuilding.server.storage.placement;

import com.rtsbuilding.rtsbuilding.network.builder.S2CRtsPlaceAnimationPayload;
import com.rtsbuilding.rtsbuilding.server.RtsStorageManager;
import com.rtsbuilding.rtsbuilding.server.camera.RtsCameraManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Sound and animation effects for RTS remote block placement.
 *
 * <p>This helper owns only auditory and visual feedback emitted after a
 * remote placement succeeds: per-block break/replace sounds, per-block
 * animation packets. It deliberately does not execute placement, extract
 * items, or manage batch jobs.
 */
public final class RtsPlacementSound {

    private RtsPlacementSound() {
    }

    /**
     * Sends a block-break animation packet to the player for the given
     * position.
     */
    public static void playRemotePlacedBlockAnimation(ServerPlayer player, BlockPos pos) {
        if (player == null || pos == null) {
            return;
        }
        PacketDistributor.sendToPlayer(player, new S2CRtsPlaceAnimationPayload(pos.immutable()));
    }

    /**
     * Plays the block-place sound for a remotely placed block.
     */
    public static void playRemotePlacedBlockSound(ServerPlayer player, ServerLevel level,
                                                   BlockPos pos) {
        if (player == null || level == null || pos == null || !level.hasChunkAt(pos)) {
            return;
        }
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return;
        }
        SoundType soundType = state.getSoundType(level, pos, player);
        Vec3 soundPos = cameraOrEyePos(player);
        RtsStorageManager.sendDirectSound(
                player,
                soundType.getPlaceSound(),
                SoundSource.BLOCKS,
                soundPos.x,
                soundPos.y,
                soundPos.z,
                (soundType.getVolume() + 1.0F) / 2.0F,
                soundType.getPitch() * 0.8F);
    }

    /**
     * Plays the block-break sound for a remotely mined/destroyed block.
     */
    public static void playRemoteBlockBreakSound(ServerPlayer player, ServerLevel level,
                                                  BlockPos pos) {
        if (player == null || level == null || pos == null || !level.hasChunkAt(pos)) {
            return;
        }
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return;
        }
        SoundType soundType = state.getSoundType(level, pos, player);
        Vec3 soundPos = cameraOrEyePos(player);
        RtsStorageManager.sendDirectSound(
                player,
                soundType.getBreakSound(),
                SoundSource.BLOCKS,
                soundPos.x,
                soundPos.y,
                soundPos.z,
                (soundType.getVolume() + 1.0F) / 2.0F,
                soundType.getPitch() * 0.8F);
    }

    private static Vec3 cameraOrEyePos(ServerPlayer player) {
        Vec3 pos = RtsCameraManager.getCameraPosition(player);
        return pos != null ? pos : player.getEyePosition();
    }
}
