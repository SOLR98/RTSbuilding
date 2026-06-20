package com.rtsbuilding.rtsbuilding.server.data;

import com.rtsbuilding.rtsbuilding.server.storage.session.RtsStorageSession;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@link RtsStorageSession} 的顶层 NBT 编解码编排器。
 *
 * <p>本类不再持有任何 NBT 键常量或子模块序列化逻辑。
 * 每个子模块（browser、sessionFlags、uiMemory、placement、destruction）
 * 各自拥有 {@code toNbt()/fromNbt()} 方法，
 * 链接存储的序列化由 {@link RtsLinkedStorageCodec} 独立处理。
 *
 * <p>向后兼容由各子模块自行维护。本类仅作为编排入口。
 */
public final class RtsStorageSessionCodec {
    public static final String ROOT_KEY = "rtsbuilding_storage_session";

    private RtsStorageSessionCodec() {
    }

    public static void load(ServerPlayer player, RtsStorageSession session, CompoundTag root) {
        RtsLinkedStorageCodec.load(player, session, root);
        session.browser.fromNbt(root);
        session.sessionFlags.fromNbt(root);
        session.uiMemory.fromNbt(player, root);
        session.placement.fromNbt(player, root);
        session.destruction.fromNbt(root);
    }

    public static CompoundTag serialize(ServerPlayer player, RtsStorageSession session) {
        CompoundTag root = new CompoundTag();
        session.browser.toNbt(root);
        session.sessionFlags.toNbt(root);
        RtsLinkedStorageCodec.save(session, root);
        session.uiMemory.toNbt(player, root);
        session.placement.toNbt(player, root);
        session.destruction.toNbt(root);
        return root;
    }
}
