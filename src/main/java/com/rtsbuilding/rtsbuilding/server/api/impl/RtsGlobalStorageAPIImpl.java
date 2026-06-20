package com.rtsbuilding.rtsbuilding.server.api.impl;

import com.rtsbuilding.rtsbuilding.api.RtsGlobalStorageAPI;
import com.rtsbuilding.rtsbuilding.server.service.ServiceRegistry;
import com.rtsbuilding.rtsbuilding.server.service.api.GlobalStorageService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

final class RtsGlobalStorageAPIImpl implements RtsGlobalStorageAPI {

    private final GlobalStorageService service;

    RtsGlobalStorageAPIImpl() {
        this.service = ServiceRegistry.getInstance().globalStorage();
    }

    @Override
    public long countItemsMatchingAllPlayers(ServerLevel level, Predicate<ItemStack> predicate) {
        return service.countAcrossAllPlayers(level, predicate);
    }

    @Override
    public long countItemAcrossAllPlayers(ServerLevel level, Item item) {
        return service.countItemAcrossAllPlayers(level, item);
    }

    @Override
    public Map<String, Long> findPlayersWithItem(ServerLevel level, Predicate<ItemStack> predicate) {
        return service.findPlayersWithItem(level, predicate);
    }

    @Override
    public void getGlobalSummary(ServerLevel level, Map<String, Long> out) {
        service.getGlobalSummary(level, out);
    }

    @Override
    public ItemStack extractFromAnyPlayer(ServerLevel level, Item item, int amount) {
        return service.extractFromAnyPlayer(level, item, amount);
    }

    @Override
    public ItemStack insertToAnyPlayer(ServerLevel level, ItemStack stack) {
        return service.insertToAnyPlayer(level, stack);
    }

    @Override
    public Set<UUID> getActiveStoragePlayers(ServerLevel level) {
        return service.getActiveStoragePlayers(level);
    }

    @Override
    public Map<String, Long> getPlayerStorage(UUID playerUuid, ServerLevel level) {
        return service.getPlayerStorage(playerUuid, level);
    }
}
