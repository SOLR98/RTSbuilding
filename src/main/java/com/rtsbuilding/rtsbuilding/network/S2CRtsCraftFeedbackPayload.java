package com.rtsbuilding.rtsbuilding.network;

import com.rtsbuilding.rtsbuilding.RtsbuildingMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record S2CRtsCraftFeedbackPayload(
        String itemId,
        int craftedCount) implements CustomPacketPayload {
    public static final Type<S2CRtsCraftFeedbackPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(RtsbuildingMod.MODID, "s2c_rts_craft_feedback"));

    public static final StreamCodec<RegistryFriendlyByteBuf, S2CRtsCraftFeedbackPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        buf.writeUtf(payload.itemId() == null ? "" : payload.itemId(), 128);
                        buf.writeVarInt(Math.max(0, payload.craftedCount()));
                    },
                    (buf) -> new S2CRtsCraftFeedbackPayload(
                            buf.readUtf(128),
                            buf.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
