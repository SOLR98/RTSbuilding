package com.rtsbuilding.rtsbuilding.server.task.persistence.asset.blueprint;

import com.rtsbuilding.rtsbuilding.common.blueprint.model.BlueprintFormat;
import com.rtsbuilding.rtsbuilding.server.task.identity.TaskId;
import com.rtsbuilding.rtsbuilding.server.task.persistence.NbtStringLimits;
import com.rtsbuilding.rtsbuilding.server.task.persistence.TaskCodec;
import com.rtsbuilding.rtsbuilding.server.task.persistence.asset.TaskAssetId;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

/** 蓝图独立 blob 的精确 schema、硬上限和内容哈希校验。 */
public final class BlueprintBlobCodec {
    public static final int CURRENT_SCHEMA = 1;
    public static final long MAX_LOGICAL_BYTES = 128L * 1024L * 1024L;
    public static final long MAX_COMPRESSED_BYTES = 32L * 1024L * 1024L;
    public static final int MAX_NBT_NODES = 2_000_000;
    public static final int MAX_BLOCKS = 1_000_000;
    private static final String KIND = "blueprint";
    private static final Set<String> EXACT_FIELDS = Set.of(
            "schema", "asset_id", "task_id", "block_count", "name", "source_name",
            "format", "sha256", "structure");

    private final TaskCodec boundedNbt = new TaskCodec();

    public BlueprintBlobRecord freeze(TaskId taskId, int blockCount, String name,
            String sourceName, String format, CompoundTag structure) {
        TaskAssetId assetId = TaskAssetId.forTask(taskId, KIND);
        BlueprintBlobRecord draft = new BlueprintBlobRecord(
                assetId, taskId, blockCount, safe(name), safe(sourceName), safe(format),
                "0".repeat(64), structure);
        validateLogical(draft);
        return new BlueprintBlobRecord(assetId, taskId, blockCount, draft.name(), draft.sourceName(),
                draft.format(), hashContent(draft), structure);
    }

    public CompoundTag encode(BlueprintBlobRecord record) {
        validateLogical(record);
        String actualHash = hashContent(record);
        if (!actualHash.equals(record.sha256())) {
            throw new BlobCodecException("蓝图 blob 内容与 sha256 不一致");
        }
        CompoundTag root = contentTag(record);
        root.putInt("schema", CURRENT_SCHEMA);
        root.putString("sha256", record.sha256());
        return root;
    }

    public BlueprintBlobRecord decode(CompoundTag root) {
        try {
            if (!root.getAllKeys().equals(EXACT_FIELDS)) {
                throw new BlobCodecException("蓝图 blob 包含缺失或未知字段");
            }
            require(root, "schema", Tag.TAG_INT);
            if (root.getInt("schema") != CURRENT_SCHEMA) {
                throw new BlobCodecException("不支持的蓝图 blob schema: " + root.getInt("schema"));
            }
            require(root, "asset_id", Tag.TAG_INT_ARRAY);
            require(root, "task_id", Tag.TAG_INT_ARRAY);
            if (!root.hasUUID("asset_id") || !root.hasUUID("task_id")) {
                throw new BlobCodecException("蓝图 blob UUID 字段损坏");
            }
            require(root, "block_count", Tag.TAG_INT);
            require(root, "name", Tag.TAG_STRING);
            require(root, "source_name", Tag.TAG_STRING);
            require(root, "format", Tag.TAG_STRING);
            require(root, "sha256", Tag.TAG_STRING);
            require(root, "structure", Tag.TAG_COMPOUND);
            BlueprintBlobRecord record = new BlueprintBlobRecord(
                    new TaskAssetId(root.getUUID("asset_id")),
                    new TaskId(root.getUUID("task_id")),
                    root.getInt("block_count"),
                    root.getString("name"), root.getString("source_name"), root.getString("format"),
                    root.getString("sha256"), root.getCompound("structure"));
            validateLogical(record);
            if (!hashContent(record).equals(record.sha256())) {
                throw new BlobCodecException("蓝图 blob sha256 校验失败");
            }
            return record;
        } catch (BlobCodecException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new BlobCodecException("蓝图 blob 字段损坏", failure);
        }
    }

    public byte[] encodeCompressed(BlueprintBlobRecord record) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            NbtIo.writeCompressed(encode(record), output);
            byte[] bytes = output.toByteArray();
            if (bytes.length > MAX_COMPRESSED_BYTES) throw new BlobCodecException("蓝图 blob 压缩文件超过 32 MiB");
            return bytes;
        } catch (IOException failure) {
            throw new BlobCodecException("编码蓝图 blob 失败", failure);
        }
    }

    private void validateLogical(BlueprintBlobRecord record) {
        if (record.blockCount() <= 0 || record.blockCount() > MAX_BLOCKS) {
            throw new BlobCodecException("蓝图 blob 方块数越界");
        }
        NbtStringLimits.requireWritable(record.name(), "blob name");
        NbtStringLimits.requireWritable(record.sourceName(), "blob sourceName");
        NbtStringLimits.requireWritable(record.format(), "blob format");
        try {
            BlueprintFormat.valueOf(record.format());
        } catch (IllegalArgumentException failure) {
            throw new BlobCodecException("不支持的蓝图格式: " + record.format(), failure);
        }
        if (!TaskAssetId.forTask(record.taskId(), KIND).equals(record.assetId())) {
            throw new BlobCodecException("蓝图 blob ID 不是由 TaskId 确定性派生");
        }
        boundedNbt.estimatePayloadBytes(record.structureView(), MAX_LOGICAL_BYTES, MAX_NBT_NODES);
    }

    private static CompoundTag contentTag(BlueprintBlobRecord record) {
        CompoundTag content = new CompoundTag();
        content.putUUID("asset_id", record.assetId().value());
        content.putUUID("task_id", record.taskId().value());
        content.putInt("block_count", record.blockCount());
        content.putString("name", record.name());
        content.putString("source_name", record.sourceName());
        content.putString("format", record.format());
        content.put("structure", record.structure());
        return content;
    }

    private static String hashContent(BlueprintBlobRecord record) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            NbtIo.writeCompressed(contentTag(record), output);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(output.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new BlobCodecException("计算蓝图 blob 哈希失败", failure);
        }
    }

    private static void require(CompoundTag root, String key, int type) {
        if (!root.contains(key, type)) throw new BlobCodecException("缺少或错误的 blob 字段: " + key);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public static final class BlobCodecException extends IllegalArgumentException {
        public BlobCodecException(String message) { super(message); }
        public BlobCodecException(String message, Throwable cause) { super(message, cause); }
    }
}
