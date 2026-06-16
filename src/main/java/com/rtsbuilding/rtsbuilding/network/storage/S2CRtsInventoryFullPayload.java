package com.rtsbuilding.rtsbuilding.network.storage;

import com.rtsbuilding.rtsbuilding.RtsbuildingMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-to-client full inventory snapshot.
 *
 * <p>Sent on initial RTS activation, after storage linking/unlinking,
 * and in response to a manual refresh request.  The client fully replaces
 * its local cache with this snapshot.
 */
public record S2CRtsInventoryFullPayload(
        long version,
        List<String> allItemIds,
        List<Long> allCounts,
        List<ItemStack> prototypes,
        List<String> fluidIds,
        List<Long> fluidAmounts,
        List<Long> fluidCapacities,
        List<String> recentIds,
        List<Long> recentAmounts,
        List<Long> recentCapacities,
        List<Byte> recentKinds) implements CustomPacketPayload {

    public static final Type<S2CRtsInventoryFullPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(RtsbuildingMod.MODID, "s2c_rts_inventory_full"));

    @SuppressWarnings("deprecation")
    public static final StreamCodec<RegistryFriendlyByteBuf, S2CRtsInventoryFullPayload> STREAM_CODEC =
            StreamCodec.of(S2CRtsInventoryFullPayload::encode, S2CRtsInventoryFullPayload::decode);

    private static void encode(RegistryFriendlyByteBuf buf, S2CRtsInventoryFullPayload p) {
        buf.writeVarLong(p.version);

        buf.writeVarInt(p.allItemIds.size());
        for (int i = 0; i < p.allItemIds.size(); i++) {
            buf.writeUtf(p.allItemIds.get(i));
            buf.writeVarLong(p.allCounts.get(i));
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, p.prototypes.get(i));
        }

        buf.writeVarInt(p.fluidIds.size());
        for (int i = 0; i < p.fluidIds.size(); i++) {
            buf.writeUtf(p.fluidIds.get(i));
            buf.writeVarLong(p.fluidAmounts.get(i));
            buf.writeVarLong(p.fluidCapacities.get(i));
        }

        buf.writeVarInt(p.recentIds.size());
        for (int i = 0; i < p.recentIds.size(); i++) {
            buf.writeUtf(p.recentIds.get(i));
            buf.writeVarLong(p.recentAmounts.get(i));
            buf.writeVarLong(p.recentCapacities.get(i));
            buf.writeByte(p.recentKinds.get(i));
        }
    }

    @SuppressWarnings("deprecation")
    private static S2CRtsInventoryFullPayload decode(RegistryFriendlyByteBuf buf) {
        long version = buf.readVarLong();

        int itemSize = buf.readVarInt();
        List<String> ids = new ArrayList<>(itemSize);
        List<Long> counts = new ArrayList<>(itemSize);
        List<ItemStack> protos = new ArrayList<>(itemSize);
        for (int i = 0; i < itemSize; i++) {
            ids.add(buf.readUtf());
            counts.add(buf.readVarLong());
            protos.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(buf));
        }

        int fluidSize = buf.readVarInt();
        List<String> fIds = new ArrayList<>(fluidSize);
        List<Long> fAmounts = new ArrayList<>(fluidSize);
        List<Long> fCapacities = new ArrayList<>(fluidSize);
        for (int i = 0; i < fluidSize; i++) {
            fIds.add(buf.readUtf());
            fAmounts.add(buf.readVarLong());
            fCapacities.add(buf.readVarLong());
        }

        int recentSize = buf.readVarInt();
        List<String> rIds = new ArrayList<>(recentSize);
        List<Long> rAmounts = new ArrayList<>(recentSize);
        List<Long> rCapacities = new ArrayList<>(recentSize);
        List<Byte> rKinds = new ArrayList<>(recentSize);
        for (int i = 0; i < recentSize; i++) {
            rIds.add(buf.readUtf());
            rAmounts.add(buf.readVarLong());
            rCapacities.add(buf.readVarLong());
            rKinds.add(buf.readByte());
        }
        return new S2CRtsInventoryFullPayload(version, ids, counts, protos,
                fIds, fAmounts, fCapacities,
                rIds, rAmounts, rCapacities, rKinds);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
