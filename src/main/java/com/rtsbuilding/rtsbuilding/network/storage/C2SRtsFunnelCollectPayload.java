package com.rtsbuilding.rtsbuilding.network.storage;

import com.rtsbuilding.rtsbuilding.RtsbuildingMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-calculated funnel collection data.
 *
 * <p>Instead of server-side AABB entity scanning every 2 ticks, the client
 * pre-calculates which {@code ItemEntity}s are within the funnel radius and
 * sends their IDs, item IDs and counts to the server.  The server validates
 * each entity (alive, in-radius) before processing.
 */
public record C2SRtsFunnelCollectPayload(
        java.util.List<Integer> entityIds,
        java.util.List<String> itemIds,
        java.util.List<Integer> counts) implements CustomPacketPayload {

    public static final Type<C2SRtsFunnelCollectPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(RtsbuildingMod.MODID, "c2s_rts_funnel_collect"));

    public static final StreamCodec<RegistryFriendlyByteBuf, C2SRtsFunnelCollectPayload> STREAM_CODEC =
            StreamCodec.of(C2SRtsFunnelCollectPayload::encode, C2SRtsFunnelCollectPayload::decode);

    private static void encode(RegistryFriendlyByteBuf buf, C2SRtsFunnelCollectPayload payload) {
        buf.writeVarInt(payload.entityIds.size());
        for (int id : payload.entityIds) {
            buf.writeVarInt(id);
        }
        for (String itemId : payload.itemIds) {
            buf.writeUtf(itemId);
        }
        for (int count : payload.counts) {
            buf.writeVarInt(count);
        }
    }

    private static C2SRtsFunnelCollectPayload decode(RegistryFriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<Integer> entityIds = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            entityIds.add(buf.readVarInt());
        }
        List<String> itemIds = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            itemIds.add(buf.readUtf());
        }
        List<Integer> counts = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            counts.add(buf.readVarInt());
        }
        return new C2SRtsFunnelCollectPayload(entityIds, itemIds, counts);
    }

    public boolean isEmpty() {
        return entityIds.isEmpty();
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
