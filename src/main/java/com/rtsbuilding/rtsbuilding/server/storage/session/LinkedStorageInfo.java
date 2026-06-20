package com.rtsbuilding.rtsbuilding.server.storage.session;

import com.rtsbuilding.rtsbuilding.server.storage.model.LinkedStorageRef;
import com.rtsbuilding.rtsbuilding.server.storage.model.StorageMetadata;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 链接存储信息模块——封装玩家已链接的所有存储方块引用及其派生数据。
 *
 * <p>内部使用单个 {@link LinkedHashMap} 维护所有元数据，
 * 天然保证插入顺序和 O(1) 键查找，消除原先 7 个平行集合的同步问题。
 *
 * <p>所有操作均为单集合操作，add/remove/set 内部保证元数据一致性。
 * 任何操作不可能产生孤立元数据。
 */
public final class LinkedStorageInfo {

    private final LinkedHashMap<LinkedStorageRef, StorageMetadata> entries = new LinkedHashMap<>();

    // ======================================================================
    //  基础查询
    // ======================================================================

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }

    public LinkedStorageRef get(int index) {
        if (index < 0 || index >= entries.size()) return null;
        int i = 0;
        for (LinkedStorageRef ref : entries.keySet()) {
            if (i++ == index) return ref;
        }
        return null;
    }

    public List<LinkedStorageRef> getAll() {
        return List.copyOf(entries.keySet());
    }

    public boolean contains(LinkedStorageRef ref) {
        return entries.containsKey(ref);
    }

    public int indexOf(LinkedStorageRef ref) {
        if (ref == null) return -1;
        int i = 0;
        for (LinkedStorageRef key : entries.keySet()) {
            if (key.equals(ref)) return i;
            i++;
        }
        return -1;
    }

    private StorageMetadata meta(LinkedStorageRef ref) {
        return entries.get(ref);
    }

    // ======================================================================
    //  名称
    // ======================================================================

    public String getName(LinkedStorageRef ref) {
        StorageMetadata m = meta(ref);
        return m != null ? m.name() : null;
    }

    public String getNameOrDefault(LinkedStorageRef ref, String fallback) {
        String name = getName(ref);
        return name != null ? name : fallback;
    }

    public void setName(LinkedStorageRef ref, String name) {
        StorageMetadata m = meta(ref);
        if (m != null) {
            entries.put(ref, m.withName(name));
        }
    }

    public String computeNameIfAbsent(LinkedStorageRef ref, Function<LinkedStorageRef, String> mappingFunction) {
        StorageMetadata m = meta(ref);
        if (m == null) return null;
        String existing = m.name();
        if (existing != null) return existing;
        String computed = mappingFunction.apply(ref);
        if (computed != null) {
            entries.put(ref, m.withName(computed));
        }
        return computed;
    }

    public Set<LinkedStorageRef> getNameKeys() {
        return entries.keySet().stream()
                .filter(r -> meta(r) != null && meta(r).name() != null)
                .collect(Collectors.toSet());
    }

    // ======================================================================
    //  模式（操作权限）
    // ======================================================================

    public byte getMode(LinkedStorageRef ref) {
        StorageMetadata m = meta(ref);
        return m != null ? m.mode() : com.rtsbuilding.rtsbuilding.server.storage.resolver.RtsLinkedStorageResolver.LINK_MODE_BIDIRECTIONAL;
    }

    public void setMode(LinkedStorageRef ref, byte mode) {
        StorageMetadata m = meta(ref);
        if (m != null) {
            entries.put(ref, m.withMode(mode));
        }
    }

    public Set<LinkedStorageRef> getModeKeys() {
        return entries.keySet();
    }

    // ======================================================================
    //  优先级
    // ======================================================================

    public int getPriority(LinkedStorageRef ref) {
        StorageMetadata m = meta(ref);
        return m != null ? m.priority() : 0;
    }

    public void setPriority(LinkedStorageRef ref, int priority) {
        StorageMetadata m = meta(ref);
        if (m != null) {
            entries.put(ref, m.withPriority(priority));
        }
    }

    public Set<LinkedStorageRef> getPriorityKeys() {
        return entries.keySet();
    }

    // ======================================================================
    //  精妙背包
    // ======================================================================

    public UUID getBackpackUuid(LinkedStorageRef ref) {
        StorageMetadata m = meta(ref);
        return m != null ? m.backpackUuid() : null;
    }

    public void setBackpackUuid(LinkedStorageRef ref, UUID uuid) {
        StorageMetadata m = meta(ref);
        if (m != null) {
            entries.put(ref, m.withBackpackUuid(uuid));
        }
    }

    public String getBackpackItemId(LinkedStorageRef ref) {
        StorageMetadata m = meta(ref);
        return m != null ? m.backpackItemId() : null;
    }

    public void setBackpackItemId(LinkedStorageRef ref, String itemId) {
        StorageMetadata m = meta(ref);
        if (m != null) {
            entries.put(ref, m.withBackpackItemId(itemId));
        }
    }

    public Set<LinkedStorageRef> getBackpackUuidKeys() {
        return entries.keySet().stream()
                .filter(r -> meta(r) != null && meta(r).backpackUuid() != null)
                .collect(Collectors.toSet());
    }

    public Set<LinkedStorageRef> getBackpackItemIdKeys() {
        return entries.keySet().stream()
                .filter(r -> meta(r) != null && meta(r).backpackItemId() != null)
                .collect(Collectors.toSet());
    }

    // ======================================================================
    //  断开连接的背包引用
    // ======================================================================

    public boolean isDetached(LinkedStorageRef ref) {
        StorageMetadata m = meta(ref);
        return m != null && m.detached();
    }

    public boolean markDetached(LinkedStorageRef ref) {
        StorageMetadata m = meta(ref);
        if (m != null && !m.detached()) {
            entries.put(ref, m.withDetached(true));
            return true;
        }
        return false;
    }

    public void removeDetached(LinkedStorageRef ref) {
        StorageMetadata m = meta(ref);
        if (m != null && m.detached()) {
            entries.put(ref, m.withDetached(false));
        }
    }

    // ======================================================================
    //  添加
    // ======================================================================

    public void add(LinkedStorageRef ref, byte mode, int priority) {
        add(ref, mode, priority, null, null);
    }

    public void add(LinkedStorageRef ref, byte mode, int priority, UUID backpackUuid, String backpackItemId) {
        String bpItem = (backpackItemId != null && !backpackItemId.isBlank()) ? backpackItemId : null;
        entries.put(ref, new StorageMetadata(null, mode, priority, backpackUuid, bpItem, false));
    }

    // ======================================================================
    //  移除
    // ======================================================================

    public boolean remove(LinkedStorageRef ref) {
        return entries.remove(ref) != null;
    }

    // ======================================================================
    //  替换
    // ======================================================================

    public void set(int index, LinkedStorageRef newRef) {
        LinkedStorageRef oldRef = get(index);
        if (oldRef != null) {
            StorageMetadata m = entries.remove(oldRef);
            if (m != null) {
                entries.put(newRef, m);
            } else {
                entries.put(newRef, StorageMetadata.createNew(
                        com.rtsbuilding.rtsbuilding.server.storage.resolver.RtsLinkedStorageResolver.LINK_MODE_BIDIRECTIONAL, 0));
            }
        } else {
            entries.put(newRef, StorageMetadata.createNew(
                    com.rtsbuilding.rtsbuilding.server.storage.resolver.RtsLinkedStorageResolver.LINK_MODE_BIDIRECTIONAL, 0));
        }
    }

    // ======================================================================
    //  清除
    // ======================================================================

    public void clear() {
        entries.clear();
    }

    public void cleanupOrphans() {
        entries.keySet().removeIf(Objects::isNull);
    }

    public boolean removeIf(Predicate<LinkedStorageRef> filter) {
        List<LinkedStorageRef> toRemove = entries.keySet().stream().filter(filter).toList();
        if (toRemove.isEmpty()) return false;
        toRemove.forEach(entries::remove);
        return true;
    }

    public boolean hasBackpackUuid(LinkedStorageRef ref) {
        StorageMetadata m = meta(ref);
        return m != null && m.backpackUuid() != null;
    }
}
