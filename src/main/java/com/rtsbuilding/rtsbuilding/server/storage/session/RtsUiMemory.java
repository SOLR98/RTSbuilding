package com.rtsbuilding.rtsbuilding.server.storage.session;

import com.rtsbuilding.rtsbuilding.server.storage.RtsStorageBindings;
import com.rtsbuilding.rtsbuilding.server.storage.RtsStorageRecentEntries;
import com.rtsbuilding.rtsbuilding.server.storage.model.GuiBinding;
import com.rtsbuilding.rtsbuilding.server.storage.model.RecentEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

public final class RtsUiMemory {

    public static final String TAG_RECENT_ENTRIES = "recent_entries";
    public static final String TAG_QUICK_SLOTS = "quick_slots";
    public static final String TAG_GUI_BINDINGS = "gui_bindings";
    private static final String TAG_RECENT_ID = "id";
    private static final String TAG_RECENT_AMOUNT = "amount";
    private static final String TAG_RECENT_CAPACITY = "capacity";
    private static final String TAG_RECENT_KIND = "kind";
    private static final String TAG_QS_SLOT = "slot";
    private static final String TAG_QS_ITEM_ID = "item_id";
    private static final String TAG_QS_STACK = "stack";
    private static final String TAG_GB_SLOT = "slot";
    private static final String TAG_GB_POS = "pos";
    private static final String TAG_GB_DIMENSION = "dimension";
    private static final String TAG_GB_FACE = "face";
    private static final String TAG_GB_LABEL = "label";
    private static final String TAG_GB_ITEM_ID = "item_id";

    private final Deque<RecentEntry> recentEntries = new ArrayDeque<>();
    private final String[] quickSlotItemIds;
    private final ItemStack[] quickSlotPreviews;
    private final GuiBinding[] guiBindings;

    public RtsUiMemory() {
        this.quickSlotItemIds = new String[RtsStorageBindings.QUICK_SLOT_COUNT];
        Arrays.fill(this.quickSlotItemIds, "");
        this.quickSlotPreviews = new ItemStack[RtsStorageBindings.QUICK_SLOT_COUNT];
        Arrays.fill(this.quickSlotPreviews, ItemStack.EMPTY);
        this.guiBindings = new GuiBinding[RtsStorageBindings.GUI_BINDING_SLOT_COUNT];
    }

    // ======================================================================
    //  最近条目
    // ======================================================================

    public Deque<RecentEntry> getRecentEntries() {
        return recentEntries;
    }

    public void addRecentEntryFirst(RecentEntry entry) {
        recentEntries.addFirst(entry);
    }

    public void addRecentEntryLast(RecentEntry entry) {
        recentEntries.addLast(entry);
    }

    public void clearRecentEntries() {
        recentEntries.clear();
    }

    // ======================================================================
    //  快捷槽物品 ID
    // ======================================================================

    public String getQuickSlotItemId(int slot) {
        if (slot < 0 || slot >= quickSlotItemIds.length) return "";
        String id = quickSlotItemIds[slot];
        return id == null ? "" : id;
    }

    public void setQuickSlotItemId(int slot, String itemId) {
        if (slot >= 0 && slot < quickSlotItemIds.length) {
            quickSlotItemIds[slot] = itemId;
        }
    }

    public String[] getQuickSlotItemIds() {
        return quickSlotItemIds;
    }

    public int getQuickSlotCount() {
        return quickSlotItemIds.length;
    }

    public void fillQuickSlotItemIds(String value) {
        Arrays.fill(quickSlotItemIds, value);
    }

    // ======================================================================
    //  快捷槽预览
    // ======================================================================

    public ItemStack getQuickSlotPreview(int slot) {
        if (slot < 0 || slot >= quickSlotPreviews.length) return ItemStack.EMPTY;
        ItemStack stack = quickSlotPreviews[slot];
        return stack == null ? ItemStack.EMPTY : stack;
    }

    public void setQuickSlotPreview(int slot, ItemStack stack) {
        if (slot >= 0 && slot < quickSlotPreviews.length) {
            quickSlotPreviews[slot] = stack;
        }
    }

    public ItemStack[] getQuickSlotPreviews() {
        return quickSlotPreviews;
    }

    public void fillQuickSlotPreviews(ItemStack stack) {
        Arrays.fill(quickSlotPreviews, stack);
    }

    // ======================================================================
    //  GUI 绑定
    // ======================================================================

    public GuiBinding getGuiBinding(int slot) {
        if (slot < 0 || slot >= guiBindings.length) return null;
        return guiBindings[slot];
    }

    public void setGuiBinding(int slot, GuiBinding binding) {
        if (slot >= 0 && slot < guiBindings.length) {
            guiBindings[slot] = binding;
        }
    }

    public GuiBinding[] getGuiBindings() {
        return guiBindings;
    }

    public int getGuiBindingCount() {
        return guiBindings.length;
    }

    public void fillGuiBindings(GuiBinding value) {
        Arrays.fill(guiBindings, value);
    }

    public void toNbt(ServerPlayer player, CompoundTag root) {
        ListTag recentList = new ListTag();
        for (RecentEntry entry : recentEntries) {
            if (entry == null || entry.id() == null || entry.id().isBlank()) continue;
            CompoundTag t = new CompoundTag();
            t.putString(TAG_RECENT_ID, entry.id());
            t.putLong(TAG_RECENT_AMOUNT, Math.max(0L, entry.amount()));
            t.putLong(TAG_RECENT_CAPACITY, Math.max(0L, entry.capacity()));
            t.putByte(TAG_RECENT_KIND, entry.kind());
            recentList.add(t);
        }
        root.put(TAG_RECENT_ENTRIES, recentList);

        ListTag qsList = new ListTag();
        for (int i = 0; i < quickSlotItemIds.length; i++) {
            String itemId = quickSlotItemIds[i];
            if (itemId == null || itemId.isBlank()) continue;
            ResourceLocation key = ResourceLocation.tryParse(itemId);
            if (key == null || !BuiltInRegistries.ITEM.containsKey(key)) continue;
            CompoundTag t = new CompoundTag();
            t.putInt(TAG_QS_SLOT, i);
            t.putString(TAG_QS_ITEM_ID, itemId);
            ItemStack preview = i < quickSlotPreviews.length && quickSlotPreviews[i] != null ? quickSlotPreviews[i] : ItemStack.EMPTY;
            if (!preview.isEmpty() && preview.is(BuiltInRegistries.ITEM.get(key))) {
                t.put(TAG_QS_STACK, preview.copyWithCount(1).save(player.registryAccess()));
            }
            qsList.add(t);
        }
        root.put(TAG_QUICK_SLOTS, qsList);

        ListTag gbList = new ListTag();
        for (int i = 0; i < guiBindings.length; i++) {
            GuiBinding b = guiBindings[i];
            if (b == null || b.pos() == null || b.dimension() == null) continue;
            CompoundTag t = new CompoundTag();
            t.putInt(TAG_GB_SLOT, i);
            t.putLong(TAG_GB_POS, b.pos().asLong());
            t.putString(TAG_GB_DIMENSION, b.dimension().location().toString());
            if (b.face() != null) t.putByte(TAG_GB_FACE, (byte) b.face().get3DDataValue());
            t.putString(TAG_GB_LABEL, b.label() != null ? b.label() : "");
            t.putString(TAG_GB_ITEM_ID, b.itemId() != null ? b.itemId() : "");
            gbList.add(t);
        }
        root.put(TAG_GUI_BINDINGS, gbList);
    }

    public void fromNbt(ServerPlayer player, CompoundTag root) {
        recentEntries.clear();
        ListTag recentList = root.getList(TAG_RECENT_ENTRIES, Tag.TAG_COMPOUND);
        for (int i = 0; i < recentList.size(); i++) {
            CompoundTag t = recentList.getCompound(i);
            String id = t.getString(TAG_RECENT_ID);
            long amount = t.getLong(TAG_RECENT_AMOUNT);
            long capacity = t.getLong(TAG_RECENT_CAPACITY);
            byte kind = t.getByte(TAG_RECENT_KIND);
            if (id.isBlank() || amount <= 0L) continue;
            recentEntries.addLast(new RecentEntry(id, amount, Math.max(0L, capacity), kind));
            if (recentEntries.size() >= RtsStorageRecentEntries.RECENT_ENTRY_LIMIT) break;
        }

        Arrays.fill(quickSlotItemIds, "");
        Arrays.fill(quickSlotPreviews, ItemStack.EMPTY);
        ListTag qsList = root.getList(TAG_QUICK_SLOTS, Tag.TAG_COMPOUND);
        for (int i = 0; i < qsList.size(); i++) {
            CompoundTag t = qsList.getCompound(i);
            int slot = t.getInt(TAG_QS_SLOT);
            String itemId = t.getString(TAG_QS_ITEM_ID);
            if (slot < 0 || slot >= quickSlotItemIds.length || itemId.isBlank()) continue;
            ResourceLocation key = ResourceLocation.tryParse(itemId);
            if (key == null || !BuiltInRegistries.ITEM.containsKey(key)) continue;
            quickSlotItemIds[slot] = itemId;
            ItemStack preview = ItemStack.EMPTY;
            if (t.contains(TAG_QS_STACK, Tag.TAG_COMPOUND)) {
                preview = ItemStack.parseOptional(player.registryAccess(), t.getCompound(TAG_QS_STACK));
                if (!preview.isEmpty() && !preview.is(BuiltInRegistries.ITEM.get(key))) preview = ItemStack.EMPTY;
            }
            quickSlotPreviews[slot] = preview.isEmpty() ? new ItemStack(BuiltInRegistries.ITEM.get(key)) : preview.copyWithCount(1);
        }

        Arrays.fill(guiBindings, null);
        ListTag gbList = root.getList(TAG_GUI_BINDINGS, Tag.TAG_COMPOUND);
        for (int i = 0; i < gbList.size(); i++) {
            CompoundTag t = gbList.getCompound(i);
            int slot = t.getInt(TAG_GB_SLOT);
            if (slot < 0 || slot >= guiBindings.length || !t.contains(TAG_GB_POS, Tag.TAG_LONG)) continue;
            String dimId = t.getString(TAG_GB_DIMENSION);
            ResourceLocation key = ResourceLocation.tryParse(dimId);
            if (key == null) continue;
            String label = t.getString(TAG_GB_LABEL);
            String itemId = t.getString(TAG_GB_ITEM_ID);
            ResourceLocation itemKey = ResourceLocation.tryParse(itemId);
            String normalizedItemId = itemKey != null && BuiltInRegistries.ITEM.containsKey(itemKey) ? itemId : "";
            Direction face = null;
            if (t.contains(TAG_GB_FACE, Tag.TAG_BYTE)) {
                int faceId = t.getByte(TAG_GB_FACE);
                if (faceId >= 0 && faceId < Direction.values().length) face = Direction.from3DDataValue(faceId);
            }
            guiBindings[slot] = new GuiBinding(BlockPos.of(t.getLong(TAG_GB_POS)).immutable(),
                    ResourceKey.create(Registries.DIMENSION, key), label, normalizedItemId, face);
        }
    }
}
