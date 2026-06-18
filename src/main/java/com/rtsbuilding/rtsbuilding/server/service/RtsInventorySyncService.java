package com.rtsbuilding.rtsbuilding.server.service;

import com.rtsbuilding.rtsbuilding.Config;
import com.rtsbuilding.rtsbuilding.network.storage.S2CRtsInventoryDeltaPayload;
import com.rtsbuilding.rtsbuilding.network.storage.S2CRtsInventoryFullPayload;
import com.rtsbuilding.rtsbuilding.server.storage.RtsStorageSession;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端库存缓存同步服务。
 *
 * <p>当关联存储内容变更时推送增量 delta 更新，首次激活或手动刷新时
 * 推送完整快照。在合并窗口内合并快速变化以避免网络风暴。
 *
 * <h3>线程模型</h3>
 * <ul>
 *   <li>{@link #queueDelta} / {@link #flushDelta} — 仅由服务端 tick 线程调用</li>
 *   <li>{@link #handleFullRequest} — 由网络线程调用，通过 {@link ConcurrentHashMap}
 *       冷却检查和 {@code volatile} 标志安全访问共享状态</li>
 *   <li>{@link #pushFull} — 可由网络线程或 tick 线程调用，所有迭代的会话字段
 *       （{@code internalFluidMb / recentEntries}）已升级为线程安全集合</li>
 * </ul>
 */
public final class RtsInventorySyncService {

    private static final Map<UUID, Long> lastFullPushTimes = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> lastRequestTimes = new ConcurrentHashMap<>();
    private static final Map<UUID, Map<String, Long>> pendingDeltas = new ConcurrentHashMap<>();

    private RtsInventorySyncService() {
    }

    // ========================================================================
    //  Delta push
    // ========================================================================

    /**
     * Queues a delta update.  Items are coalesced: if multiple calls arrive
     * for the same itemId within the merge window, only the latest count is
     * kept.  Call {@link #flushDelta} once per tick after all mutations.
     */
    public static void queueDelta(ServerPlayer player, Map<String, Long> itemChanges) {
        if (player == null || itemChanges == null || itemChanges.isEmpty()) return;
        pendingDeltas.compute(player.getUUID(), (uuid, existing) -> {
            if (existing == null) existing = new HashMap<>();
            existing.putAll(itemChanges);
            return existing;
        });
    }

    /**
     * Sends a coalesced delta payload, then clears pending deltas.
     */
    public static void flushDelta(ServerPlayer player, RtsStorageSession session) {
        if (player == null || session == null) return;
        Map<String, Long> deltas = pendingDeltas.remove(player.getUUID());
        if (deltas == null || deltas.isEmpty()) return;

        long version = session.transfer.pageDataVersion.incrementAndGet();
        List<String> itemIds = new ArrayList<>(deltas.size());
        List<Long> counts = new ArrayList<>(deltas.size());
        for (var entry : deltas.entrySet()) {
            itemIds.add(entry.getKey());
            counts.add(entry.getValue());
        }
        session.transfer.storageViewDirty = false;
        PacketDistributor.sendToPlayer(player,
                new S2CRtsInventoryDeltaPayload(version, itemIds, counts));
    }

    // ========================================================================
    //  Full snapshot
    // ========================================================================

    /** Handles a client full-snapshot request with cooldown enforcement. */
    public static void handleFullRequest(ServerPlayer player, long clientVersion) {
        if (player == null) return;
        RtsStorageSession session = RtsSessionService.getIfPresent(player);
        if (session == null) return;

        long now = System.currentTimeMillis();
        int requestCd = Config.INVENTORY_FULL_REQUEST_COOLDOWN_MS.get();
        Long lastReq = lastRequestTimes.get(player.getUUID());
        if (lastReq != null && now - lastReq < requestCd) return;
        lastRequestTimes.put(player.getUUID(), now);

        long serverVersion = session.transfer.pageDataVersion.get();
        if (clientVersion == serverVersion && clientVersion > 0) {
            session.transfer.storageViewDirty = false;
            return;
        }

        pushFull(player, session);
    }

    /**
     * Builds and sends a full inventory snapshot immediately (with push CD).
     */
    public static void pushFull(ServerPlayer player, RtsStorageSession session) {
        if (player == null || session == null) return;

        long now = System.currentTimeMillis();
        int pushCd = Config.INVENTORY_FULL_PUSH_COOLDOWN_MS.get();
        Long lastPush = lastFullPushTimes.get(player.getUUID());
        if (lastPush != null && now - lastPush < pushCd) return;
        lastFullPushTimes.put(player.getUUID(), now);

        long version = session.transfer.pageDataVersion.get();

        Map<String, Long> counts = new HashMap<>();
        Map<String, ItemStack> protos = new HashMap<>();
        var agg = RtsStorageTickService.INSTANCE.getStorage(player);
        if (agg != null) {
            agg.collectAllItems(counts, protos);
        }

        List<String> itemIds = new ArrayList<>(counts.size());
        List<Long> itemCounts = new ArrayList<>(counts.size());
        List<ItemStack> protoList = new ArrayList<>(counts.size());
        for (var entry : counts.entrySet()) {
            itemIds.add(entry.getKey());
            itemCounts.add(entry.getValue());
            ItemStack proto = protos.getOrDefault(entry.getKey(), ItemStack.EMPTY);
            protoList.add(proto.isEmpty() ? ItemStack.EMPTY : proto);
        }

        List<String> fluidIds = new ArrayList<>();
        List<Long> fluidAmounts = new ArrayList<>();
        List<Long> fluidCapacities = new ArrayList<>();
        for (var entry : session.internalFluidMb.entrySet()) {
            fluidIds.add(entry.getKey());
            fluidAmounts.add(entry.getValue());
        }

        List<String> recentIds = new ArrayList<>();
        List<Long> recentAmounts = new ArrayList<>();
        List<Long> recentCapacities = new ArrayList<>();
        List<Byte> recentKinds = new ArrayList<>();
        for (var recent : session.recentEntries) {
            recentIds.add(recent.id() != null ? recent.id() : "");
            recentAmounts.add(recent.amount());
            recentCapacities.add(recent.capacity());
            recentKinds.add(recent.kind());
        }

        session.transfer.storageViewDirty = false;
        PacketDistributor.sendToPlayer(player, new S2CRtsInventoryFullPayload(
                version, itemIds, itemCounts, protoList,
                fluidIds, fluidAmounts, fluidCapacities,
                recentIds, recentAmounts, recentCapacities, recentKinds));
    }

    /** Clears cached state for a disconnected player. */
    public static void clear(ServerPlayer player) {
        if (player == null) return;
        UUID uuid = player.getUUID();
        lastFullPushTimes.remove(uuid);
        lastRequestTimes.remove(uuid);
        pendingDeltas.remove(uuid);
    }
}
