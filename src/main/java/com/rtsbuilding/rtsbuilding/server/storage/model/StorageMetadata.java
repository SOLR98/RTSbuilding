package com.rtsbuilding.rtsbuilding.server.storage.model;

import com.rtsbuilding.rtsbuilding.server.storage.resolver.RtsLinkedStorageResolver;

import java.util.UUID;

/**
 * 链接存储的完整元数据聚合记录，替代原先 7 个平行集合。
 *
 * @param name             缓存显示名称
 * @param mode             操作权限位掩码
 * @param priority         AE 风格优先级
 * @param backpackUuid     精妙背包 UUID，可为 null
 * @param backpackItemId   精妙背包物品 ID，可为 null
 * @param detached         是否已断开连接
 */
public record StorageMetadata(
        String name,
        byte mode,
        int priority,
        UUID backpackUuid,
        String backpackItemId,
        boolean detached) {

    public static StorageMetadata createNew(byte mode, int priority) {
        return new StorageMetadata(null, mode, priority, null, null, false);
    }

    public static StorageMetadata createNew(byte mode, int priority, UUID backpackUuid, String backpackItemId) {
        return new StorageMetadata(null, mode, priority, backpackUuid, backpackItemId, false);
    }

    public byte getMode() {
        return mode;
    }

    public int getPriority() {
        return priority;
    }

    public StorageMetadata withName(String name) {
        return new StorageMetadata(name, mode, priority, backpackUuid, backpackItemId, detached);
    }

    public StorageMetadata withMode(byte mode) {
        return new StorageMetadata(name, RtsLinkedStorageResolver.sanitizeLinkMode(mode), priority, backpackUuid, backpackItemId, detached);
    }

    public StorageMetadata withPriority(int priority) {
        return new StorageMetadata(name, mode, RtsLinkedStorageResolver.sanitizeLinkedStoragePriority(priority), backpackUuid, backpackItemId, detached);
    }

    public StorageMetadata withBackpackUuid(UUID uuid) {
        return new StorageMetadata(name, mode, priority, uuid, backpackItemId, detached);
    }

    public StorageMetadata withBackpackItemId(String itemId) {
        return new StorageMetadata(name, mode, priority, backpackUuid, itemId, detached);
    }

    public StorageMetadata withDetached(boolean detached) {
        return new StorageMetadata(name, mode, priority, backpackUuid, backpackItemId, detached);
    }
}
