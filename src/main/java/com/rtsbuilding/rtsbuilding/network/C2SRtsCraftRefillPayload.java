package com.rtsbuilding.rtsbuilding.network;

import java.util.ArrayList;
import java.util.List;

import com.rtsbuilding.rtsbuilding.RtsbuildingMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record C2SRtsCraftRefillPayload(List<String> blueprintItemIds) implements CustomPacketPayload {
    private static final int BLUEPRINT_SIZE = 9;
    public static final Type<C2SRtsCraftRefillPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(RtsbuildingMod.MODID, "c2s_rts_craft_refill"));

    public static final StreamCodec<RegistryFriendlyByteBuf, C2SRtsCraftRefillPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        List<String> ids = payload.blueprintItemIds();
                        for (int i = 0; i < BLUEPRINT_SIZE; i++) {
                            String value = ids != null && i < ids.size() ? ids.get(i) : "";
                            buf.writeUtf(value == null ? "" : value, 128);
                        }
                    },
                    (buf) -> {
                        List<String> ids = new ArrayList<>(BLUEPRINT_SIZE);
                        for (int i = 0; i < BLUEPRINT_SIZE; i++) {
                            ids.add(buf.readUtf(128));
                        }
                        return new C2SRtsCraftRefillPayload(ids);
                    });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
