package com.rtsbuilding.rtsbuilding.network.storage;

import com.rtsbuilding.rtsbuilding.RtsbuildingMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 批量链接存储容器荷载（C→S）。
 *
 * <p>将多个方块位置一次性发送到服务端批量链接，替代逐包发送。
 * 单包最多携带 256 个位置，超出部分由客户端切分。
 */
public record C2SRtsBatchLinkStoragePayload(
        List<BlockPos> positions,
        byte linkMode
) implements CustomPacketPayload {

    public static final int MAX_POSITIONS = 256;

    public static final Type<C2SRtsBatchLinkStoragePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(RtsbuildingMod.MODID, "c2s_rts_batch_link_storage"));

    public static final StreamCodec<FriendlyByteBuf, C2SRtsBatchLinkStoragePayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeVarInt(payload.positions.size());
                for (BlockPos pos : payload.positions) {
                    buf.writeBlockPos(pos);
                }
                buf.writeByte(payload.linkMode);
            },
            buf -> {
                int count = Math.min(buf.readVarInt(), MAX_POSITIONS);
                List<BlockPos> list = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    list.add(buf.readBlockPos());
                }
                byte mode = buf.readByte();
                return new C2SRtsBatchLinkStoragePayload(Collections.unmodifiableList(list), mode);
            }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
