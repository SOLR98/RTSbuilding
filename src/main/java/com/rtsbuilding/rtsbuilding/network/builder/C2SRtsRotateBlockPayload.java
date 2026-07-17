package com.rtsbuilding.rtsbuilding.network.builder;

import com.rtsbuilding.rtsbuilding.RtsbuildingMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record C2SRtsRotateBlockPayload(BlockPos pos, String propertyName, String valueName)
        implements CustomPacketPayload {
    public static final int MAX_PROPERTY_NAME_CHARS = 64;
    public static final int MAX_VALUE_NAME_CHARS = 64;

    public static final Type<C2SRtsRotateBlockPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(RtsbuildingMod.MODID, "c2s_rts_rotate_block"));

    public static final StreamCodec<RegistryFriendlyByteBuf, C2SRtsRotateBlockPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeBlockPos(payload.pos());
                buf.writeUtf(payload.propertyName(), MAX_PROPERTY_NAME_CHARS);
                buf.writeUtf(payload.valueName(), MAX_VALUE_NAME_CHARS);
            },
            (buf) -> new C2SRtsRotateBlockPayload(
                    buf.readBlockPos(),
                    buf.readUtf(MAX_PROPERTY_NAME_CHARS),
                    buf.readUtf(MAX_VALUE_NAME_CHARS)));

    public C2SRtsRotateBlockPayload {
        propertyName = fit(propertyName, MAX_PROPERTY_NAME_CHARS);
        valueName = fit(valueName, MAX_VALUE_NAME_CHARS);
    }

    public C2SRtsRotateBlockPayload(BlockPos pos) {
        this(pos, "", "");
    }

    private static String fit(String value, int maxChars) {
        String safe = value == null ? "" : value;
        return safe.length() <= maxChars ? safe : safe.substring(0, maxChars);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
