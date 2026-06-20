package com.rtsbuilding.rtsbuilding.server.storage.session;

import com.rtsbuilding.rtsbuilding.network.storage.RtsStorageSort;
import com.rtsbuilding.rtsbuilding.server.storage.RtsStoragePageBuilder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.HashSet;
import java.util.Set;

public class RtsBrowserState {

    public static final String TAG_PAGE = "page";
    public static final String TAG_SEARCH = "search";
    public static final String TAG_CATEGORY = "category";
    public static final String TAG_SORT = "sort";
    public static final String TAG_ASCENDING = "ascending";
    public static final String TAG_CRAFT_SEARCH = "craft_search";
    public static final String TAG_CRAFT_SHOW_UNAVAILABLE = "craft_show_unavailable";
    public static final String TAG_CRAFT_REQUESTED_COUNT = "craft_requested_count";

    public static final int CRAFTABLE_BATCH_SIZE = 12;

    public int page;
    public int pageSize = RtsStoragePageBuilder.DEFAULT_PAGE_SIZE;
    public String search = "";
    public String category = "all";
    public RtsStorageSort sort = RtsStorageSort.QUANTITY;
    public boolean ascending = false;
    public boolean pinyinSearchEnabled;
    public final Set<String> localizedSearchMatches = new HashSet<>();

    public String craftSearch = "";
    public boolean craftShowUnavailable;
    public int craftRequestedCount = CRAFTABLE_BATCH_SIZE;
    public boolean craftPinyinSearchEnabled;
    public final Set<String> craftLocalizedSearchMatches = new HashSet<>();

    public void toNbt(CompoundTag root) {
        root.putInt(TAG_PAGE, Math.max(0, page));
        root.putString(TAG_SEARCH, sanitizeText(search, 128));
        root.putString(TAG_CATEGORY, RtsStoragePageBuilder.normalizeCategory(category));
        root.putInt(TAG_SORT, (sort == null ? RtsStorageSort.QUANTITY : sort).ordinal());
        root.putBoolean(TAG_ASCENDING, ascending);
        root.putString(TAG_CRAFT_SEARCH, sanitizeText(craftSearch, 128));
        root.putBoolean(TAG_CRAFT_SHOW_UNAVAILABLE, craftShowUnavailable);
        root.putInt(TAG_CRAFT_REQUESTED_COUNT, Math.max(CRAFTABLE_BATCH_SIZE, Math.min(999, craftRequestedCount)));
    }

    public void fromNbt(CompoundTag root) {
        page = root.contains(TAG_PAGE, Tag.TAG_INT) ? Math.max(0, root.getInt(TAG_PAGE)) : 0;
        search = sanitizeText(root.getString(TAG_SEARCH), 128);
        category = RtsStoragePageBuilder.normalizeCategory(root.getString(TAG_CATEGORY));
        sort = parseSavedSort(root.getInt(TAG_SORT));
        ascending = root.contains(TAG_ASCENDING, Tag.TAG_BYTE) && root.getBoolean(TAG_ASCENDING);
        craftSearch = sanitizeText(root.getString(TAG_CRAFT_SEARCH), 128);
        craftShowUnavailable = root.contains(TAG_CRAFT_SHOW_UNAVAILABLE, Tag.TAG_BYTE) && root.getBoolean(TAG_CRAFT_SHOW_UNAVAILABLE);
        craftRequestedCount = root.contains(TAG_CRAFT_REQUESTED_COUNT, Tag.TAG_INT)
                ? Math.max(CRAFTABLE_BATCH_SIZE, Math.min(999, root.getInt(TAG_CRAFT_REQUESTED_COUNT)))
                : CRAFTABLE_BATCH_SIZE;
    }

    private static String sanitizeText(String value, int maxLength) {
        if (value == null || value.isBlank()) return "";
        String clean = value.trim();
        int limit = Math.max(0, maxLength);
        return clean.length() <= limit ? clean : clean.substring(0, limit);
    }

    private static RtsStorageSort parseSavedSort(int ordinal) {
        RtsStorageSort[] values = RtsStorageSort.values();
        return (ordinal >= 0 && ordinal < values.length) ? values[ordinal] : RtsStorageSort.QUANTITY;
    }
}
