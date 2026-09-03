package com.sails.ai.selfserviceapi.file.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sails.ai.selfserviceapi.file.config.FileStorageProperties;
import com.sails.ai.selfserviceapi.file.exception.FileContentNotFoundException;
import com.sails.ai.selfserviceapi.file.exception.FileStorageException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

class LocalFileStorageTest {

    @TempDir
    Path root;

    private LocalFileStorage storage;

    @BeforeEach
    void setUp() {
        storage = new LocalFileStorage(properties(root));
    }

    @Test
    void storesAndReadsBackTheSameBytes() throws IOException {
        String objectName = ObjectPaths.object("user-1", 7L, "file-a");
        store(objectName, "hello");

        try (InputStream in = storage.open(objectName)) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("hello");
        }
    }

    @Test
    void refusesToOverwriteAnExistingObject() {
        String objectName = ObjectPaths.object("user-1", 7L, "file-a");
        store(objectName, "original");

        assertThatThrownBy(() -> store(objectName, "replacement"))
                .isInstanceOf(FileStorageException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void reportsAMissingObjectAsNotFoundRatherThanAnError() {
        assertThatThrownBy(() -> storage.open(ObjectPaths.object("user-1", 7L, "never-written")))
                .isInstanceOf(FileContentNotFoundException.class);
    }

    @Test
    void deleteIsIdempotent() {
        String objectName = ObjectPaths.object("user-1", 7L, "file-a");
        store(objectName, "hello");

        assertThat(storage.delete(objectName)).isTrue();
        assertThat(storage.delete(objectName)).isFalse();
    }

    /** The shape purge depends on: one prefix, every POC that user touched, one call. */
    @Test
    void deletingAUserPrefixRemovesTheirFilesAcrossEveryPoc() {
        store(ObjectPaths.object("user-1", 7L, "file-a"), "a");
        store(ObjectPaths.object("user-1", 7L, "file-b"), "b");
        store(ObjectPaths.object("user-1", 9L, "file-c"), "c");
        store(ObjectPaths.object("user-2", 7L, "file-d"), "d");

        assertThat(storage.deleteByPrefix(ObjectPaths.userPrefix("user-1"))).isEqualTo(3);

        assertThatThrownBy(() -> storage.open(ObjectPaths.object("user-1", 9L, "file-c")))
                .isInstanceOf(FileContentNotFoundException.class);
        assertThat(storage.delete(ObjectPaths.object("user-2", 7L, "file-d"))).isTrue();
    }

    @Test
    void deletingAPrefixWithNothingUnderItIsNotAnError() {
        assertThat(storage.deleteByPrefix(ObjectPaths.userPrefix("never-uploaded"))).isZero();
    }

    @Test
    void refusesAnObjectNameThatEscapesTheStorageRoot() {
        assertThatThrownBy(() -> store("users/../../etc/passwd", "nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("escapes");
    }

    private void store(String objectName, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        storage.store(objectName, "text/plain", new ByteArrayInputStream(bytes));
    }

    private static FileStorageProperties properties(Path localDir) {
        return new FileStorageProperties(
                "local", null, localDir.toString(), DataSize.ofMegabytes(10), 20,
                DataSize.ofMegabytes(250), List.of("text/plain"));
    }
}
