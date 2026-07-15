package com.rtsbuilding.rtsbuilding.server.task.persistence.asset.blueprint;

import com.rtsbuilding.rtsbuilding.server.task.identity.TaskId;
import com.rtsbuilding.rtsbuilding.server.task.persistence.asset.TaskAssetId;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtomicBlueprintBlobRepositoryTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void deterministicPerTaskIdWritesOnceAndReloadsWithHashVerification() {
        BlueprintBlobCodec codec = new BlueprintBlobCodec();
        AtomicBlueprintBlobRepository repository = repository(codec);
        TaskId taskId = TaskId.create();
        BlueprintBlobRecord record = codec.freeze(
                taskId, 1, "house", "test", "VANILLA_NBT", structure(new byte[]{1, 2, 3}));

        assertEquals(TaskAssetId.forTask(taskId, "blueprint"), record.assetId());
        assertEquals(AtomicBlueprintBlobRepository.WriteOutcome.WRITTEN, repository.writeOnce(record));
        assertEquals(AtomicBlueprintBlobRepository.WriteOutcome.ALREADY_PRESENT, repository.writeOnce(record));

        var found = assertInstanceOf(
                AtomicBlueprintBlobRepository.LoadResult.Found.class, repository.load(record.assetId()));
        assertEquals(record.sha256(), found.record().sha256());
        assertEquals(taskId, found.record().taskId());
        assertArrayEquals(new byte[]{1, 2, 3},
                found.record().structure().getByteArray("payload"));
        assertEquals(1, repository.scan().assetIds().size());
    }

    @Test
    void sameDeterministicIdWithDifferentContentFailsClosedInsteadOfOverwriting() {
        BlueprintBlobCodec codec = new BlueprintBlobCodec();
        AtomicBlueprintBlobRepository repository = repository(codec);
        TaskId taskId = TaskId.create();
        BlueprintBlobRecord first = codec.freeze(
                taskId, 1, "first", "test", "VANILLA_NBT", structure(new byte[]{1}));
        BlueprintBlobRecord conflicting = codec.freeze(
                taskId, 1, "second", "test", "VANILLA_NBT", structure(new byte[]{2}));
        assertNotEquals(first.sha256(), conflicting.sha256());
        repository.writeOnce(first);

        assertThrows(AtomicBlueprintBlobRepository.BlobRepositoryException.class,
                () -> repository.writeOnce(conflicting));
        var found = assertInstanceOf(
                AtomicBlueprintBlobRepository.LoadResult.Found.class, repository.load(first.assetId()));
        assertEquals(first.sha256(), found.record().sha256());
    }

    @Test
    void corruptExistingBlobIsNotTreatedAsMissingOrOverwritten() throws IOException {
        BlueprintBlobCodec codec = new BlueprintBlobCodec();
        AtomicBlueprintBlobRepository repository = repository(codec);
        BlueprintBlobRecord record = codec.freeze(
                TaskId.create(), 1, "house", "test", "VANILLA_NBT", structure(new byte[]{1}));
        Files.createDirectories(temporaryDirectory);
        Files.write(temporaryDirectory.resolve(record.assetId() + ".nbt"), new byte[]{9, 8, 7});

        assertInstanceOf(AtomicBlueprintBlobRepository.LoadResult.Failed.class,
                repository.load(record.assetId()));
        assertThrows(AtomicBlueprintBlobRepository.BlobRepositoryException.class,
                () -> repository.writeOnce(record));
        assertArrayEquals(new byte[]{9, 8, 7},
                Files.readAllBytes(temporaryDirectory.resolve(record.assetId() + ".nbt")));
    }

    @Test
    void blobLargerThanFourMiBRemainsCompatibleWithExistingBlueprintLimits() {
        BlueprintBlobCodec codec = new BlueprintBlobCodec();
        AtomicBlueprintBlobRepository repository = repository(codec);
        byte[] logicalPayload = new byte[5 * 1024 * 1024];
        for (int i = 0; i < logicalPayload.length; i += 4096) logicalPayload[i] = (byte) (i / 4096);
        BlueprintBlobRecord record = codec.freeze(
                TaskId.create(), 200_000, "large", "compat", "VANILLA_NBT", structure(logicalPayload));

        assertEquals(AtomicBlueprintBlobRepository.WriteOutcome.WRITTEN, repository.writeOnce(record));
        var found = assertInstanceOf(
                AtomicBlueprintBlobRepository.LoadResult.Found.class, repository.load(record.assetId()));
        assertEquals(logicalPayload.length, found.record().structure().getByteArray("payload").length);
        assertTrue(found.compressedBytes() <= BlueprintBlobCodec.MAX_COMPRESSED_BYTES);
    }

    @Test
    void hashGuardPreventsDeletingDifferentPhysicalContent() {
        BlueprintBlobCodec codec = new BlueprintBlobCodec();
        AtomicBlueprintBlobRepository repository = repository(codec);
        BlueprintBlobRecord record = codec.freeze(
                TaskId.create(), 1, "house", "test", "VANILLA_NBT", structure(new byte[]{1}));
        repository.writeOnce(record);

        assertThrows(AtomicBlueprintBlobRepository.BlobRepositoryException.class,
                () -> repository.deleteIfMatches(record.assetId(), "0".repeat(64)));
        assertInstanceOf(AtomicBlueprintBlobRepository.LoadResult.Found.class,
                repository.load(record.assetId()));
        assertTrue(repository.deleteIfMatches(record.assetId(), record.sha256()));
        assertInstanceOf(AtomicBlueprintBlobRepository.LoadResult.Missing.class,
                repository.load(record.assetId()));
    }

    @Test
    void startupScanDeletesOnlyRecognizedCrashTemporaryFiles() throws IOException {
        BlueprintBlobCodec codec = new BlueprintBlobCodec();
        AtomicBlueprintBlobRepository repository = repository(codec);
        TaskAssetId id = TaskAssetId.forTask(TaskId.create(), "blueprint");
        Path temporary = temporaryDirectory.resolve(id + ".nbt." + java.util.UUID.randomUUID() + ".tmp");
        Files.createDirectories(temporaryDirectory);
        Files.write(temporary, new byte[]{1, 2, 3});

        AtomicBlueprintBlobRepository.ScanResult scan = repository.scan();

        assertEquals(1, scan.removedTemporaryFiles());
        assertTrue(Files.notExists(temporary));
    }

    @Test
    void unsupportedBlueprintFormatFailsBeforeWrite() {
        BlueprintBlobCodec codec = new BlueprintBlobCodec();
        CompoundTag structure = structure(new byte[]{1});
        TaskId taskId = TaskId.create();
        BlueprintBlobRecord invalid = new BlueprintBlobRecord(
                TaskAssetId.forTask(taskId, "blueprint"), taskId, 1,
                "bad", "test", "NOT_A_REAL_FORMAT", "0".repeat(64), structure);

        assertThrows(BlueprintBlobCodec.BlobCodecException.class, () -> codec.encode(invalid));
    }

    @Test
    void competingWriteOnceCallsConvergeOnSameContent() throws Exception {
        BlueprintBlobCodec codec = new BlueprintBlobCodec();
        BlueprintBlobRecord record = codec.freeze(
                TaskId.create(), 1, "house", "race", "VANILLA_NBT", structure(new byte[]{1, 2, 3}));
        AtomicBlueprintBlobRepository first = repository(codec);
        AtomicBlueprintBlobRepository second = repository(codec);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstWrite = executor.submit(() -> {
                start.await();
                return first.writeOnce(record);
            });
            var secondWrite = executor.submit(() -> {
                start.await();
                return second.writeOnce(record);
            });
            start.countDown();
            List<AtomicBlueprintBlobRepository.WriteOutcome> outcomes =
                    List.of(firstWrite.get(2, TimeUnit.SECONDS), secondWrite.get(2, TimeUnit.SECONDS));
            assertTrue(outcomes.contains(AtomicBlueprintBlobRepository.WriteOutcome.WRITTEN));
            assertTrue(outcomes.contains(AtomicBlueprintBlobRepository.WriteOutcome.ALREADY_PRESENT));
        }
        assertInstanceOf(AtomicBlueprintBlobRepository.LoadResult.Found.class,
                repository(codec).load(record.assetId()));
    }

    private AtomicBlueprintBlobRepository repository(BlueprintBlobCodec codec) {
        return new AtomicBlueprintBlobRepository(temporaryDirectory, codec);
    }

    private static CompoundTag structure(byte[] payload) {
        CompoundTag structure = new CompoundTag();
        structure.putByteArray("payload", payload);
        return structure;
    }
}
