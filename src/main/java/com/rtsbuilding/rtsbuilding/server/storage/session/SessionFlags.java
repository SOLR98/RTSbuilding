package com.rtsbuilding.rtsbuilding.server.storage.session;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

public final class SessionFlags {

    public static final String TAG_USE_BD_NETWORK = "use_bd_network";
    public static final String TAG_AUTO_STORE_MINED_DROPS = "auto_store_mined_drops";
    public static final String TAG_INTERNAL_FLUIDS = "internal_fluids";
    private static final String TAG_FLUID_ID = "id";
    private static final String TAG_FLUID_AMOUNT = "amount";

    public boolean useBdNetwork = true;
    public boolean autoStoreMinedDrops = true;

    public final Map<String, Long> internalFluidMb = new HashMap<>();

    public void toNbt(CompoundTag root) {
        root.putBoolean(TAG_USE_BD_NETWORK, useBdNetwork);
        root.putBoolean(TAG_AUTO_STORE_MINED_DROPS, autoStoreMinedDrops);
        ListTag fluids = new ListTag();
        for (var entry : internalFluidMb.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) continue;
            long amount = entry.getValue() != null ? entry.getValue() : 0L;
            if (amount <= 0L) continue;
            CompoundTag ft = new CompoundTag();
            ft.putString(TAG_FLUID_ID, entry.getKey());
            ft.putLong(TAG_FLUID_AMOUNT, amount);
            fluids.add(ft);
        }
        root.put(TAG_INTERNAL_FLUIDS, fluids);
    }

    public void fromNbt(CompoundTag root) {
        useBdNetwork = !root.contains(TAG_USE_BD_NETWORK, Tag.TAG_BYTE) || root.getBoolean(TAG_USE_BD_NETWORK);
        autoStoreMinedDrops = !root.contains(TAG_AUTO_STORE_MINED_DROPS, Tag.TAG_BYTE) || root.getBoolean(TAG_AUTO_STORE_MINED_DROPS);
        internalFluidMb.clear();
        ListTag fluids = root.getList(TAG_INTERNAL_FLUIDS, Tag.TAG_COMPOUND);
        for (int i = 0; i < fluids.size(); i++) {
            CompoundTag ft = fluids.getCompound(i);
            String fluidId = ft.getString(TAG_FLUID_ID);
            long amount = ft.getLong(TAG_FLUID_AMOUNT);
            if (fluidId.isBlank() || amount <= 0L) continue;
            ResourceLocation key = ResourceLocation.tryParse(fluidId);
            if (key == null || !BuiltInRegistries.FLUID.containsKey(key)) continue;
            internalFluidMb.put(fluidId, amount);
        }
    }
}
