package com.rtsbuilding.rtsbuilding.server.service.impl;

import com.rtsbuilding.rtsbuilding.server.service.RtsStorageTickService;
import com.rtsbuilding.rtsbuilding.server.service.ServiceRegistry;
import com.rtsbuilding.rtsbuilding.server.service.api.GlobalStorageService;
import com.rtsbuilding.rtsbuilding.server.storage.cache.RtsAggregateStorage;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.*;
import java.util.function.Predicate;

public final class RtsGlobalStorageServiceImpl implements GlobalStorageService {

    @Override
    public long countAcrossAllPlayers(ServerLevel level, Predicate<ItemStack> predicate) {
        if (level == null || predicate == null) return 0L;
        long total = 0L;
        for (var entry : ServiceRegistry.getInstance().session().allSessions().entrySet()) {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player == null) continue;
            RtsAggregateStorage storage = RtsStorageTickService.INSTANCE.getStorage(player);
            if (storage == null || storage.isEmpty()) continue;
            Map<String, Long> counts = new HashMap<>();
            storage.getAvailableItems(counts);
            for (var itemEntry : counts.entrySet()) {
                ItemStack proto = storage.getPrototype(itemEntry.getKey());
                if (proto != null && !proto.isEmpty() && predicate.test(proto)) {
                    total += itemEntry.getValue();
                }
            }
        }
        return total;
    }

    @Override
    public long countItemAcrossAllPlayers(ServerLevel level, Item item) {
        if (level == null || item == null) return 0L;
        long total = 0L;
        for (var entry : ServiceRegistry.getInstance().session().allSessions().entrySet()) {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player == null) continue;
            RtsAggregateStorage storage = RtsStorageTickService.INSTANCE.getStorage(player);
            if (storage != null) {
                total += storage.getTotalCount(item);
            }
        }
        return total;
    }

    @Override
    public Map<String, Long> findPlayersWithItem(ServerLevel level, Predicate<ItemStack> predicate) {
        Map<String, Long> result = new LinkedHashMap<>();
        if (level == null || predicate == null) return result;
        for (var entry : ServiceRegistry.getInstance().session().allSessions().entrySet()) {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player == null) continue;
            RtsAggregateStorage storage = RtsStorageTickService.INSTANCE.getStorage(player);
            if (storage == null || storage.isEmpty()) continue;
            Map<String, Long> counts = new HashMap<>();
            storage.getAvailableItems(counts);
            long playerTotal = 0L;
            for (var itemEntry : counts.entrySet()) {
                ItemStack proto = storage.getPrototype(itemEntry.getKey());
                if (proto != null && !proto.isEmpty() && predicate.test(proto)) {
                    playerTotal += itemEntry.getValue();
                }
            }
            if (playerTotal > 0) {
                result.put(player.getGameProfile().getName(), playerTotal);
            }
        }
        return result;
    }

    @Override
    public void getGlobalSummary(ServerLevel level, Map<String, Long> counts) {
        if (level == null || counts == null) return;
        for (var entry : ServiceRegistry.getInstance().session().allSessions().entrySet()) {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player == null) continue;
            RtsAggregateStorage storage = RtsStorageTickService.INSTANCE.getStorage(player);
            if (storage == null || storage.isEmpty()) continue;
            storage.getAvailableItems(counts);
        }
    }

    @Override
    public ItemStack extractFromAnyPlayer(ServerLevel level, Item item, int amount) {
        if (level == null || item == null || amount <= 0) return ItemStack.EMPTY;
        for (var entry : ServiceRegistry.getInstance().session().allSessions().entrySet()) {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player == null) continue;
            RtsAggregateStorage storage = RtsStorageTickService.INSTANCE.getStorage(player);
            if (storage == null || storage.isEmpty()) continue;
            ItemStack result = storage.executeExtractRoute(item, null, amount);
            if (!result.isEmpty()) {
                RtsStorageTickService.INSTANCE.alert(player.getUUID());
                return result;
            }
        }
        return ItemStack.EMPTY;
    }

    @Override
    public ItemStack insertToAnyPlayer(ServerLevel level, ItemStack stack) {
        if (level == null || stack == null || stack.isEmpty()) return ItemStack.EMPTY;
        for (var entry : ServiceRegistry.getInstance().session().allSessions().entrySet()) {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player == null) continue;
            RtsAggregateStorage storage = RtsStorageTickService.INSTANCE.getStorage(player);
            if (storage == null || storage.isEmpty()) continue;
            ItemStack remain = storage.executeInsertRoute(stack, false);
            if (remain.isEmpty() || remain.getCount() < stack.getCount()) {
                RtsStorageTickService.INSTANCE.alert(player.getUUID());
            }
            if (remain.isEmpty()) return ItemStack.EMPTY;
            stack = remain;
        }
        return stack;
    }

    @Override
    public Set<UUID> getActiveStoragePlayers(ServerLevel level) {
        Set<UUID> result = new HashSet<>();
        if (level == null) return result;
        for (var entry : ServiceRegistry.getInstance().session().allSessions().entrySet()) {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player == null) continue;
            RtsAggregateStorage storage = RtsStorageTickService.INSTANCE.getStorage(player);
            if (storage != null && !storage.isEmpty()) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    @Override
    public Map<String, Long> getPlayerStorage(UUID playerUuid, ServerLevel level) {
        Map<String, Long> result = new HashMap<>();
        if (playerUuid == null || level == null) return result;
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(playerUuid);
        if (player == null) return result;
        RtsAggregateStorage storage = RtsStorageTickService.INSTANCE.getStorage(player);
        if (storage != null && !storage.isEmpty()) {
            storage.getAvailableItems(result);
        }
        return result;
    }
}
