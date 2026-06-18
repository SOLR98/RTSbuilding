package com.rtsbuilding.rtsbuilding.client.cache;

import com.rtsbuilding.rtsbuilding.client.record.CraftableEntry;
import com.rtsbuilding.rtsbuilding.client.record.FluidEntry;
import com.rtsbuilding.rtsbuilding.client.record.RecentEntry;
import com.rtsbuilding.rtsbuilding.client.record.StorageEntry;
import com.rtsbuilding.rtsbuilding.network.storage.S2CRtsInventoryDeltaPayload;
import com.rtsbuilding.rtsbuilding.network.storage.S2CRtsInventoryFullPayload;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 客户端库存缓存 —— 存储关联存储内容的完整快照，支持本地搜索、排序、筛选和
 * 分页，无需服务端往返。
 *
 * <p>通过 {@link #applyDelta}（增量更新）和 {@link #applyFull}（全量替换）更新。
 * 存储内容变更时服务端自动推送 delta。
 */
public final class RtsClientInventoryCache {

    public static final RtsClientInventoryCache INSTANCE = new RtsClientInventoryCache();

    private final Map<String, Long> itemCounts = new HashMap<>();
    private final Map<String, ItemStack> prototypes = new HashMap<>();
    private final Map<String, Long> fluidAmounts = new HashMap<>();
    private final Map<String, Long> fluidCapacities = new HashMap<>();
    private final List<RecentEntry> recentEntries = new ArrayList<>();
    private final List<CraftableEntry> craftableEntries = new ArrayList<>();

    private long version;
    private boolean dirty;

    private String currentSearch = "";
    private String currentCategory = "all";
    private int currentSort;
    private boolean ascending;
    private int currentPage;
    private int pageSize = 90;

    private List<StorageEntry> filteredEntries = new ArrayList<>();
    private String filterHash = "";

    // ========================================================================
    //  Network updates
    // ========================================================================

    public void applyDelta(S2CRtsInventoryDeltaPayload payload) {
        for (int i = 0; i < payload.changedItemIds().size(); i++) {
            String id = payload.changedItemIds().get(i);
            long count = payload.newCounts().get(i);
            if (count <= 0) {
                itemCounts.remove(id);
                prototypes.remove(id);
            } else {
                itemCounts.put(id, count);
            }
        }
        this.version = payload.version();
        this.dirty = false;
        invalidateView();
    }

    /**
     * 客户端乐观 delta 更新，在服务端确认前完成拾取/归还。
     * 负值表示取出物品，正值表示存回物品。
     * 服务端下一次真实 delta 或 full push 将纠正任何偏差。
     */
    public void applyDelta(String itemId, long delta) {
        if (delta == 0L) return;
        long current = itemCounts.getOrDefault(itemId, 0L);
        long next = Math.max(0L, current + delta);
        if (next <= 0L) {
            itemCounts.remove(itemId);
            prototypes.remove(itemId);
        } else {
            itemCounts.put(itemId, next);
        }
        invalidateView();
    }

    /** 返回指定物品注册表字符串 ID 的缓存计数。 */
    public long getCount(String itemId) {
        return itemCounts.getOrDefault(itemId, 0L);
    }

    public void applyFull(S2CRtsInventoryFullPayload payload) {
        itemCounts.clear();
        prototypes.clear();
        for (int i = 0; i < payload.allItemIds().size(); i++) {
            String id = payload.allItemIds().get(i);
            itemCounts.put(id, payload.allCounts().get(i));
            ItemStack proto = payload.prototypes().get(i);
            if (proto != null && !proto.isEmpty()) {
                prototypes.put(id, proto);
            }
        }

        fluidAmounts.clear();
        fluidCapacities.clear();
        for (int i = 0; i < payload.fluidIds().size(); i++) {
            fluidAmounts.put(payload.fluidIds().get(i), payload.fluidAmounts().get(i));
            fluidCapacities.put(payload.fluidIds().get(i), payload.fluidCapacities().get(i));
        }

        recentEntries.clear();
        for (int i = 0; i < payload.recentIds().size(); i++) {
            recentEntries.add(new RecentEntry(
                    false,
                    payload.recentIds().get(i),
                    "",
                    payload.recentAmounts().get(i),
                    payload.recentCapacities().get(i),
                    payload.recentKinds().get(i),
                    ItemStack.EMPTY));
        }

        this.version = payload.version();
        this.dirty = false;
        invalidateView();
    }

    // ========================================================================
    //  Search / Sort / Pagination
    // ========================================================================

    public void setSearch(String search) {
        if (search == null) search = "";
        if (this.currentSearch.equals(search)) return;
        this.currentSearch = search;
        this.currentPage = 0;
        invalidateView();
    }

    public void setCategory(String category) {
        if (category == null) category = "all";
        if (this.currentCategory.equals(category)) return;
        this.currentCategory = category;
        this.currentPage = 0;
        invalidateView();
    }

    public void setSort(int sortOrdinal, boolean ascending) {
        if (this.currentSort == sortOrdinal && this.ascending == ascending) return;
        this.currentSort = sortOrdinal;
        this.ascending = ascending;
        this.currentPage = 0;
        invalidateView();
    }

    public void setPage(int page) {
        this.currentPage = Math.max(0, Math.min(page, getTotalPages() - 1));
    }

    public void nextPage() {
        if (currentPage < getTotalPages() - 1) currentPage++;
    }

    public void prevPage() {
        if (currentPage > 0) currentPage--;
    }

    public void setPageSize(int size) {
        this.pageSize = Math.max(1, Math.min(size, 200));
        this.currentPage = 0;
        invalidateView();
    }

    // ========================================================================
    //  Query methods
    // ========================================================================

    public List<StorageEntry> getCurrentPage() {
        ensureFiltered();
        int start = currentPage * pageSize;
        int end = Math.min(start + pageSize, filteredEntries.size());
        if (start >= filteredEntries.size()) return Collections.emptyList();
        return new ArrayList<>(filteredEntries.subList(start, end));
    }

    public int getTotalPages() {
        ensureFiltered();
        if (filteredEntries.isEmpty()) return 1;
        return (filteredEntries.size() + pageSize - 1) / pageSize;
    }

    public int getTotalEntries() {
        ensureFiltered();
        return filteredEntries.size();
    }

    public int getCurrentPageIndex() {
        return currentPage;
    }

    public String getSearch() {
        return currentSearch;
    }

    public List<StorageEntry> getAllEntries() {
        ensureFiltered();
        return Collections.unmodifiableList(filteredEntries);
    }

    public long getAvailableCount(String itemId) {
        return itemCounts.getOrDefault(itemId, 0L);
    }

    public ItemStack getPrototype(String itemId) {
        return prototypes.getOrDefault(itemId, ItemStack.EMPTY);
    }

    public boolean hasItem(String itemId) {
        Long count = itemCounts.get(itemId);
        return count != null && count > 0;
    }

    public List<FluidEntry> getFluidEntries() {
        List<FluidEntry> list = new ArrayList<>();
        for (var entry : fluidAmounts.entrySet()) {
            String id = entry.getKey();
            list.add(new FluidEntry(id, "", entry.getValue(),
                    fluidCapacities.getOrDefault(id, 0L), "", "", ItemStack.EMPTY));
        }
        return list;
    }

    public List<RecentEntry> getRecentEntries() {
        return Collections.unmodifiableList(recentEntries);
    }

    public List<CraftableEntry> getCraftableEntries() {
        return Collections.unmodifiableList(craftableEntries);
    }

    public void setCraftableEntries(List<CraftableEntry> entries) {
        craftableEntries.clear();
        if (entries != null) craftableEntries.addAll(entries);
    }

    public long getVersion() {
        return version;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void markDirty() {
        this.dirty = true;
    }

    // ========================================================================
    //  Internal filtering
    // ========================================================================

    private void invalidateView() {
        filterHash = "";
    }

    private void ensureFiltered() {
        String hash = buildFilterHash();
        if (hash.equals(filterHash)) return;
        filterHash = hash;
        rebuildFilteredList();
    }

    private String buildFilterHash() {
        return version + "|" + currentSearch + "|" + currentCategory + "|" + currentSort + "|" + ascending;
    }

    private void rebuildFilteredList() {
        List<StorageEntry> list = new ArrayList<>(itemCounts.size());
        for (var mapEntry : itemCounts.entrySet()) {
            String id = mapEntry.getKey();
            long count = mapEntry.getValue();
            if (count <= 0) continue;
            if (!matchesSearch(id)) continue;
            if (!matchesCategory(id)) continue;
            ItemStack proto = prototypes.getOrDefault(id, ItemStack.EMPTY);
            String mod = extractMod(id);
            String name = extractName(id, proto);
            list.add(new StorageEntry(proto.isEmpty() ? ItemStack.EMPTY : proto, id, count, mod, name));
        }
        sortEntries(list);
        filteredEntries = list;
    }

    private boolean matchesSearch(String itemId) {
        if (currentSearch.isEmpty()) return true;
        String lower = currentSearch.toLowerCase(Locale.ROOT);
        if (itemId.toLowerCase(Locale.ROOT).contains(lower)) return true;
        ItemStack proto = prototypes.get(itemId);
        if (proto != null && !proto.isEmpty()) {
            String name = proto.getHoverName().getString().toLowerCase(Locale.ROOT);
            if (name.contains(lower)) return true;
        }
        return false;
    }

    private boolean matchesCategory(String itemId) {
        if ("all".equals(currentCategory)) return true;
        int colonIdx = itemId.indexOf(':');
        if (colonIdx > 0) {
            return itemId.substring(0, colonIdx).equalsIgnoreCase(currentCategory);
        }
        return false;
    }

    private void sortEntries(List<StorageEntry> list) {
        Comparator<StorageEntry> comparator = switch (currentSort) {
            case 0 -> Comparator.comparingLong(StorageEntry::count);
            case 1 -> Comparator.comparing(StorageEntry::itemId);
            default -> Comparator.comparingLong(StorageEntry::count);
        };
        if (!ascending) comparator = comparator.reversed();
        list.sort(comparator);
    }

    private static String extractMod(String itemId) {
        int idx = itemId.indexOf(':');
        return idx > 0 ? itemId.substring(0, idx) : "";
    }

    private static String extractName(String itemId, ItemStack proto) {
        if (proto != null && !proto.isEmpty()) {
            return proto.getHoverName().getString();
        }
        return itemId;
    }
}
