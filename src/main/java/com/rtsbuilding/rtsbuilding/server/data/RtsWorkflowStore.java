package com.rtsbuilding.rtsbuilding.server.data;

import com.rtsbuilding.rtsbuilding.server.workflow.service.RtsWorkflowSlotManager;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * World-save level persistence for workflow entries.
 *
 * <p>Stores all players' workflow slot managers as a single compressed NBT file
 * in the world save directory ({@code rtsbuilding/workflow_data.dat}).
 * Follows the same file I/O pattern as {@link RtsStorageSessionStore}.</p>
 *
 * <p>This is a standalone store — callers (typically {@code RtsWorkflowEngine})
 * manage the slots in memory and invoke {@link #saveAll(MinecraftServer, Map)}
 * / {@link #loadPlayer(MinecraftServer, UUID)} at lifecycle boundaries.</p>
 */
public final class RtsWorkflowStore {
    private static final String DIRECTORY = "rtsbuilding";
    private static final String FILE_NAME = "workflow_data.dat";
    private static final String TEMP_FILE_NAME = "workflow_data.dat.tmp";
    private static final String KEY_DATA_VERSION = "data_version";
    private static final String KEY_PLAYERS = "players";
    private static final int DATA_VERSION = 1;

    // Per-player keys
    private static final String KEY_NEXT_ID = "next_id";
    private static final String KEY_DIMENSIONS = "dimensions";
    private static final String KEY_SLOT_MANAGER = "slots";

    private RtsWorkflowStore() {
    }

    // ──────────────────────────────────────────────────────────────────
    //  Save — persist all players' workflow data
    // ──────────────────────────────────────────────────────────────────

    /**
     * Saves all workflow slot managers for all players to the world save file.
     *
     * @param server     the Minecraft server (used to derive the world save path)
     * @param allSlots   the map of player UUID → dimension → slot managers
     */
    public static synchronized void saveAll(MinecraftServer server,
                                            Map<UUID, Map<ResourceKey<Level>, RtsWorkflowSlotManager>> allSlots) {
        if (server == null || allSlots == null) {
            return;
        }

        CompoundTag root = new CompoundTag();
        root.putInt(KEY_DATA_VERSION, DATA_VERSION);

        CompoundTag players = new CompoundTag();
        for (Map.Entry<UUID, Map<ResourceKey<Level>, RtsWorkflowSlotManager>> playerEntry : allSlots.entrySet()) {
            UUID playerId = playerEntry.getKey();
            Map<ResourceKey<Level>, RtsWorkflowSlotManager> dimSlots = playerEntry.getValue();
            if (dimSlots == null || dimSlots.isEmpty()) {
                continue;
            }

            CompoundTag playerTag = new CompoundTag();
            CompoundTag dimensions = new CompoundTag();
            boolean hasData = false;

            for (Map.Entry<ResourceKey<Level>, RtsWorkflowSlotManager> dimEntry : dimSlots.entrySet()) {
                ResourceKey<Level> dimension = dimEntry.getKey();
                RtsWorkflowSlotManager slots = dimEntry.getValue();
                if (slots == null || slots.occupiedCount() == 0) {
                    continue;
                }
                CompoundTag slotsTag = slots.saveToNbt();
                if (slotsTag != null) {
                    dimensions.put(dimension.location().toString(), slotsTag);
                    hasData = true;
                }
            }

            if (hasData) {
                playerTag.put(KEY_DIMENSIONS, dimensions);
                players.put(playerId.toString(), playerTag);
            }
        }

        root.put(KEY_PLAYERS, players);
        writeAll(server, root);
    }

    // ──────────────────────────────────────────────────────────────────
    //  Load — restore a single player's workflow data
    // ──────────────────────────────────────────────────────────────────

    /**
     * Loads workflow slot managers for a specific player from the world save file.
     *
     * @param server   the Minecraft server
     * @param playerId the player's UUID
     * @return a map of dimension → slot manager (empty map if none saved)
     */
    public static synchronized Map<ResourceKey<Level>, RtsWorkflowSlotManager> loadPlayer(
            MinecraftServer server, UUID playerId) {
        Map<ResourceKey<Level>, RtsWorkflowSlotManager> result = new HashMap<>();
        if (server == null || playerId == null) {
            return result;
        }

        CompoundTag root = loadAll(server);
        if (root.isEmpty()) {
            return result;
        }

        CompoundTag players = root.getCompound(KEY_PLAYERS);
        if (players.isEmpty()) {
            return result;
        }

        String playerKey = playerId.toString();
        if (!players.contains(playerKey)) {
            return result;
        }

        CompoundTag playerTag = players.getCompound(playerKey);
        CompoundTag dimensions = playerTag.getCompound(KEY_DIMENSIONS);
        for (String dimKey : dimensions.getAllKeys()) {
            ResourceLocation dimLocation = ResourceLocation.tryParse(dimKey);
            if (dimLocation == null) {
                continue;
            }
            ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimLocation);
            CompoundTag slotsTag = dimensions.getCompound(dimKey);
            if (slotsTag != null && !slotsTag.isEmpty()) {
                RtsWorkflowSlotManager slots = RtsWorkflowSlotManager.loadFromNbt(slotsTag);
                if (slots.occupiedCount() > 0) {
                    result.put(dimension, slots);
                }
            }
        }

        return result;
    }

    // ──────────────────────────────────────────────────────────────────
    //  File I/O
    // ──────────────────────────────────────────────────────────────────

    private static final String KEY_REGISTRY = "minecraft:dimension_type";

    /**
     * Returns the {@link ResourceKey} for a dimension from its string representation.
     */
    private static ResourceKey<Level> parseDimensionKey(String dimKey) {
        ResourceLocation location = ResourceLocation.tryParse(dimKey);
        if (location == null) {
            return Level.OVERWORLD;
        }
        return ResourceKey.create(Registries.DIMENSION, location);
    }

    private static CompoundTag loadAll(MinecraftServer server) {
        Path path = storagePath(server);
        if (!Files.isRegularFile(path)) {
            return emptyRoot();
        }
        try {
            CompoundTag root = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
            if (root == null) {
                return emptyRoot();
            }
            if (!root.contains(KEY_PLAYERS)) {
                root.put(KEY_PLAYERS, new CompoundTag());
            }
            return root;
        } catch (IOException | RuntimeException ignored) {
            return emptyRoot();
        }
    }

    private static CompoundTag emptyRoot() {
        CompoundTag root = new CompoundTag();
        root.putInt(KEY_DATA_VERSION, DATA_VERSION);
        root.put(KEY_PLAYERS, new CompoundTag());
        return root;
    }

    private static void writeAll(MinecraftServer server, CompoundTag root) {
        Path path = storagePath(server);
        Path tempPath = path.resolveSibling(TEMP_FILE_NAME);
        try {
            Files.createDirectories(path.getParent());
            NbtIo.writeCompressed(root, tempPath);
            try {
                Files.move(tempPath, path, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveFailed) {
                Files.move(tempPath, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException ignored) {
            try {
                Files.deleteIfExists(tempPath);
            } catch (IOException deleteIgnored) {
            }
        }
    }

    private static Path storagePath(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve(DIRECTORY).resolve(FILE_NAME);
    }
}
