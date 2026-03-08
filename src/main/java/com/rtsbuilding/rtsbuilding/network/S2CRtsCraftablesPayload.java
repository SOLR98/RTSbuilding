package com.rtsbuilding.rtsbuilding.network;

import java.util.ArrayList;
import java.util.List;

import com.rtsbuilding.rtsbuilding.RtsbuildingMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record S2CRtsCraftablesPayload(
        String search,
        boolean showUnavailable,
        List<String> recipeIds,
        List<String> resultItemIds,
        List<Integer> resultCounts,
        List<Boolean> craftable,
        List<String> missingSummaries) implements CustomPacketPayload {
    public static final Type<S2CRtsCraftablesPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(RtsbuildingMod.MODID, "s2c_rts_craftables"));

    public static final StreamCodec<RegistryFriendlyByteBuf, S2CRtsCraftablesPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        buf.writeUtf(payload.search() == null ? "" : payload.search(), 128);
                        buf.writeBoolean(payload.showUnavailable());
                        int size = Math.min(
                                payload.recipeIds().size(),
                                Math.min(
                                        payload.resultItemIds().size(),
                                        Math.min(
                                                payload.resultCounts().size(),
                                                Math.min(payload.craftable().size(), payload.missingSummaries().size()))));
                        buf.writeVarInt(size);
                        for (int i = 0; i < size; i++) {
                            buf.writeUtf(payload.recipeIds().get(i), 256);
                            buf.writeUtf(payload.resultItemIds().get(i), 128);
                            buf.writeVarInt(payload.resultCounts().get(i));
                            buf.writeBoolean(payload.craftable().get(i));
                            buf.writeUtf(payload.missingSummaries().get(i), 512);
                        }
                    },
                    (buf) -> {
                        String search = buf.readUtf(128);
                        boolean showUnavailable = buf.readBoolean();
                        int size = buf.readVarInt();
                        List<String> recipeIds = new ArrayList<>(size);
                        List<String> resultItemIds = new ArrayList<>(size);
                        List<Integer> resultCounts = new ArrayList<>(size);
                        List<Boolean> craftable = new ArrayList<>(size);
                        List<String> missingSummaries = new ArrayList<>(size);
                        for (int i = 0; i < size; i++) {
                            recipeIds.add(buf.readUtf(256));
                            resultItemIds.add(buf.readUtf(128));
                            resultCounts.add(buf.readVarInt());
                            craftable.add(buf.readBoolean());
                            missingSummaries.add(buf.readUtf(512));
                        }
                        return new S2CRtsCraftablesPayload(
                                search,
                                showUnavailable,
                                recipeIds,
                                resultItemIds,
                                resultCounts,
                                craftable,
                                missingSummaries);
                    });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
