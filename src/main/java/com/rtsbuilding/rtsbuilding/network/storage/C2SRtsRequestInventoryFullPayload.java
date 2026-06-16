package com.rtsbuilding.rtsbuilding.network.storage;

import com.rtsbuilding.rtsbuilding.RtsbuildingMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client-to-server request for a full inventory snapshot.
 *
 * <p>Sent when the player clicks the refresh button.  The server enforces
 * a cooldown ({@code inventoryFullRequestCooldownMs}) to prevent spam.
 * If {@code clientVersion} matches the server's current version the server
 * may skip the response entirely.
 */
public record C2SRtsRequestInventoryFullPayload(long clientVersion) implements CustomPacketPayload {

    public static final Type<C2SRtsRequestInventoryFullPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(RtsbuildingMod.MODID, "c2s_rts_inventory_full_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, C2SRtsRequestInventoryFullPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> buf.writeVarLong(payload.clientVersion()),
                    (buf) -> new C2SRtsRequestInventoryFullPayload(buf.readVarLong()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
