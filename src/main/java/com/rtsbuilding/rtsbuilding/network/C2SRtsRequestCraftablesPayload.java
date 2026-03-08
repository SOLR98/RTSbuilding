package com.rtsbuilding.rtsbuilding.network;

import com.rtsbuilding.rtsbuilding.RtsbuildingMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record C2SRtsRequestCraftablesPayload(
        String search,
        boolean showUnavailable) implements CustomPacketPayload {
    public static final Type<C2SRtsRequestCraftablesPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(RtsbuildingMod.MODID, "c2s_rts_request_craftables"));

    public static final StreamCodec<RegistryFriendlyByteBuf, C2SRtsRequestCraftablesPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        buf.writeUtf(payload.search() == null ? "" : payload.search(), 128);
                        buf.writeBoolean(payload.showUnavailable());
                    },
                    (buf) -> new C2SRtsRequestCraftablesPayload(
                            buf.readUtf(128),
                            buf.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
