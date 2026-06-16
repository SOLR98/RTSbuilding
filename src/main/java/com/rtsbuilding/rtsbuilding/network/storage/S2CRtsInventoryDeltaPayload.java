package com.rtsbuilding.rtsbuilding.network.storage;

import com.rtsbuilding.rtsbuilding.RtsbuildingMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-to-client incremental inventory update.
 *
 * <p>Sent when linked storage contents change.  The client merges these
 * deltas into its local {@code RtsClientInventoryCache}.  A count of 0
 * means the item was fully depleted and should be removed from the cache.
 *
 * <p>All three lists are guaranteed to have the same length.
 */
public record S2CRtsInventoryDeltaPayload(
        long version,
        List<String> changedItemIds,
        List<Long> newCounts) implements CustomPacketPayload {

    public static final Type<S2CRtsInventoryDeltaPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(RtsbuildingMod.MODID, "s2c_rts_inventory_delta"));

    public static final StreamCodec<RegistryFriendlyByteBuf, S2CRtsInventoryDeltaPayload> STREAM_CODEC =
            StreamCodec.of(S2CRtsInventoryDeltaPayload::encode, S2CRtsInventoryDeltaPayload::decode);

    private static void encode(RegistryFriendlyByteBuf buf, S2CRtsInventoryDeltaPayload payload) {
        buf.writeVarLong(payload.version);
        int size = payload.changedItemIds.size();
        buf.writeVarInt(size);
        for (int i = 0; i < size; i++) {
            buf.writeUtf(payload.changedItemIds.get(i));
            buf.writeVarLong(payload.newCounts.get(i));
        }
    }

    private static S2CRtsInventoryDeltaPayload decode(RegistryFriendlyByteBuf buf) {
        long version = buf.readVarLong();
        int size = buf.readVarInt();
        List<String> itemIds = new ArrayList<>(size);
        List<Long> counts = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            itemIds.add(buf.readUtf());
            counts.add(buf.readVarLong());
        }
        return new S2CRtsInventoryDeltaPayload(version, itemIds, counts);
    }

    public boolean isEmpty() {
        return changedItemIds.isEmpty();
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
