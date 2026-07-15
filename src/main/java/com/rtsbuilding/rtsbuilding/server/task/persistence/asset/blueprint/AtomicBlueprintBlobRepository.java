package com.rtsbuilding.rtsbuilding.server.task.persistence.asset.blueprint;

import com.rtsbuilding.rtsbuilding.server.task.persistence.asset.TaskAssetId;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每任务独占蓝图 blob 的原子 write-once 仓库。
 *
 * <p>该仓库不读 Player/Level/Session，也不发布 TaskSnapshot。正确顺序由上层保证：后台先成功 writeOnce，
 * 主线程再原子提交 manifest metadata + 轻量 task。相同 ID 的完全相同内容可幂等重试；相同 ID 的不同 hash
 * 必须 fail-closed，绝不覆盖。</p>
 */
public final class AtomicBlueprintBlobRepository {
    public static final int MAX_SCAN_ENTRIES = 100_000;
    public static final long MAX_SCAN_COMPRESSED_BYTES = 4L * 1024L * 1024L * 1024L;
    private static final ConcurrentHashMap<TaskAssetId, Object> WRITE_LOCKS = new ConcurrentHashMap<>();

    private final Path directory;
    private final BlueprintBlobCodec codec;

    public AtomicBlueprintBlobRepository(MinecraftServer server, BlueprintBlobCodec codec) {
        this(Objects.requireNonNull(server, "server").getWorldPath(LevelResource.ROOT)
                .resolve("rtsbuilding/task_blobs/blueprint"), codec);
    }

    /** 包内文件入口仅用于故障注入测试。 */
    AtomicBlueprintBlobRepository(Path directory, BlueprintBlobCodec codec) {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    public WriteOutcome writeOnce(BlueprintBlobRecord record) {
        Objects.requireNonNull(record, "record");
        Object lock = WRITE_LOCKS.computeIfAbsent(record.assetId(), ignored -> new Object());
        synchronized (lock) {
            return writeOnceLocked(record);
        }
    }

    private WriteOutcome writeOnceLocked(BlueprintBlobRecord record) {
        Path target = path(record.assetId());
        if (Files.exists(target)) {
            BlueprintBlobRecord existing = requireFound(load(record.assetId()));
            requireSame(existing, record);
            return WriteOutcome.ALREADY_PRESENT;
        }

        byte[] encoded = codec.encodeCompressed(record);
        Path temporary = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.createDirectories(directory);
            Files.write(temporary, encoded, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            if (!moveNew(temporary, target)) {
                BlueprintBlobRecord existing = requireFound(load(record.assetId()));
                requireSame(existing, record);
                return WriteOutcome.ALREADY_PRESENT;
            }
            BlueprintBlobRecord verified = requireFound(load(record.assetId()));
            requireSame(verified, record);
            return WriteOutcome.WRITTEN;
        } catch (IOException | RuntimeException failure) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw new BlobRepositoryException("原子写入蓝图 blob 失败: " + record.assetId(), failure);
        }
    }

    public synchronized LoadResult load(TaskAssetId assetId) {
        Objects.requireNonNull(assetId, "assetId");
        Path file = path(assetId);
        if (!Files.exists(file)) return new LoadResult.Missing();
        try {
            if (!Files.isRegularFile(file)) throw new IOException("blob 路径不是普通文件");
            long compressedBytes = Files.size(file);
            if (compressedBytes <= 0L || compressedBytes > BlueprintBlobCodec.MAX_COMPRESSED_BYTES) {
                throw new IOException("blob 压缩文件大小越界: " + compressedBytes);
            }
            byte[] bytes = Files.readAllBytes(file);
            CompoundTag root = NbtIo.readCompressed(new ByteArrayInputStream(bytes),
                    NbtAccounter.create(BlueprintBlobCodec.MAX_LOGICAL_BYTES + 1024L * 1024L));
            if (root == null) throw new IOException("blob NBT 根标签为空");
            BlueprintBlobRecord record = codec.decode(root);
            if (!record.assetId().equals(assetId)) throw new IOException("blob 文件名与内容 ID 不一致");
            return new LoadResult.Found(record, compressedBytes);
        } catch (IOException | RuntimeException failure) {
            return new LoadResult.Failed(failure);
        }
    }

    /** 仅在 manifest 已 durable 移除 metadata/建立 tombstone 后调用；hash 不符时拒绝误删。 */
    public synchronized boolean deleteIfMatches(TaskAssetId assetId, String sha256) {
        LoadResult loaded = load(assetId);
        if (loaded instanceof LoadResult.Missing) return false;
        BlueprintBlobRecord record = requireFound(loaded);
        if (!record.sha256().equals(sha256)) {
            throw new BlobRepositoryException("拒绝删除 hash 不匹配的蓝图 blob: " + assetId);
        }
        try {
            return Files.deleteIfExists(path(assetId));
        } catch (IOException failure) {
            throw new BlobRepositoryException("删除蓝图 blob 失败: " + assetId, failure);
        }
    }

    /** 启动/GC 使用的有界物理目录扫描；超过数量或压缩总字节上限立即 fail-closed。 */
    public synchronized ScanResult scan() {
        if (!Files.exists(directory)) return new ScanResult(List.of(), 0L, 0);
        List<TaskAssetId> ids = new ArrayList<>();
        long totalBytes = 0L;
        int visited = 0;
        int removedTemps = 0;
        try (var files = Files.list(directory)) {
            var iterator = files.iterator();
            while (iterator.hasNext()) {
                Path file = iterator.next();
                if (++visited > MAX_SCAN_ENTRIES) throw new BlobRepositoryException("蓝图 blob 目录项超过扫描上限");
                if (!Files.isRegularFile(file)) continue;
                String name = file.getFileName().toString();
                if (name.endsWith(".tmp")) {
                    int marker = name.indexOf(".nbt.");
                    if (marker <= 0) throw new BlobRepositoryException("发现未知临时 blob 文件: " + name);
                    String idText = name.substring(0, marker);
                    TaskAssetId.parse(idText);
                    Files.deleteIfExists(file);
                    removedTemps++;
                    continue;
                }
                if (!name.endsWith(".nbt")) continue;
                totalBytes = Math.addExact(totalBytes, Files.size(file));
                if (totalBytes > MAX_SCAN_COMPRESSED_BYTES) {
                    throw new BlobRepositoryException("蓝图 blob 压缩总量超过 4 GiB");
                }
                ids.add(TaskAssetId.parse(name.substring(0, name.length() - 4)));
            }
        } catch (IOException | ArithmeticException failure) {
            throw new BlobRepositoryException("扫描蓝图 blob 目录失败", failure);
        }
        ids.sort(Comparator.naturalOrder());
        return new ScanResult(ids, totalBytes, removedTemps);
    }

    private static boolean moveNew(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (FileAlreadyExistsException raced) {
            Files.deleteIfExists(temporary);
            return false;
        } catch (AtomicMoveNotSupportedException unsupported) {
            try {
                Files.move(temporary, target);
                return true;
            } catch (FileAlreadyExistsException raced) {
                Files.deleteIfExists(temporary);
                return false;
            }
        }
    }

    private Path path(TaskAssetId assetId) {
        Path resolved = directory.resolve(assetId + ".nbt").normalize();
        if (!resolved.getParent().equals(directory)) throw new IllegalArgumentException("assetId 路径越界");
        return resolved;
    }

    private static BlueprintBlobRecord requireFound(LoadResult result) {
        if (result instanceof LoadResult.Found found) return found.record();
        if (result instanceof LoadResult.Failed failed) {
            throw new BlobRepositoryException("读取现有蓝图 blob 失败，拒绝覆盖", failed.cause());
        }
        throw new BlobRepositoryException("预期蓝图 blob 存在但文件缺失");
    }

    private static void requireSame(BlueprintBlobRecord existing, BlueprintBlobRecord requested) {
        if (!existing.assetId().equals(requested.assetId())
                || !existing.taskId().equals(requested.taskId())
                || !existing.sha256().equals(requested.sha256())
                || existing.blockCount() != requested.blockCount()) {
            throw new BlobRepositoryException("相同 assetId 已存在不同蓝图内容，拒绝覆盖: " + requested.assetId());
        }
    }

    public enum WriteOutcome { WRITTEN, ALREADY_PRESENT }

    public sealed interface LoadResult permits LoadResult.Found, LoadResult.Missing, LoadResult.Failed {
        record Found(BlueprintBlobRecord record, long compressedBytes) implements LoadResult { }
        record Missing() implements LoadResult { }
        record Failed(Throwable cause) implements LoadResult {
            public Failed { Objects.requireNonNull(cause, "cause"); }
        }
    }

    public record ScanResult(List<TaskAssetId> assetIds, long compressedBytes, int removedTemporaryFiles) {
        public ScanResult { assetIds = List.copyOf(assetIds); }
    }

    public static final class BlobRepositoryException extends IllegalStateException {
        public BlobRepositoryException(String message) { super(message); }
        public BlobRepositoryException(String message, Throwable cause) { super(message, cause); }
    }
}
