package com.sails.ai.selfserviceapi.file.storage;

import com.sails.ai.selfserviceapi.file.config.FileStorageProperties;
import com.sails.ai.selfserviceapi.file.exception.FileContentNotFoundException;
import com.sails.ai.selfserviceapi.file.exception.FileStorageException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Writes objects to a directory on this machine. Selected by {@code files.storage=local}, which is
 * the default, for the same reason {@code pipeline.executor=skip} is: a developer with no GCP
 * credentials should still be able to run and exercise the feature rather than hit a credentials
 * error at startup.
 *
 * <p>Not a production backend, and not pretending to be one — Cloud Run's filesystem is
 * memory-backed and per-instance, so anything written here is gone when the instance is replaced.
 */
@Component
@ConditionalOnProperty(prefix = "files", name = "storage", havingValue = "local", matchIfMissing = true)
public class LocalFileStorage implements FileStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalFileStorage.class);

    private final Path root;

    public LocalFileStorage(FileStorageProperties properties) {
        String configured = properties.localDir();
        this.root = (configured == null || configured.isBlank()
                ? Path.of(System.getProperty("java.io.tmpdir"), "self-service-files")
                : Path.of(configured)).toAbsolutePath().normalize();
        log.info("User files are stored locally under {} — not durable, and not for production", root);
    }

    @Override
    public void store(String objectName, String contentType, InputStream content) {
        Path target = resolve(objectName);
        try {
            Files.createDirectories(target.getParent());
            // CREATE_NEW: same collision guarantee as the GCS backend's doesNotExist().
            Files.copy(content, target);
        } catch (FileAlreadyExistsException e) {
            throw new FileStorageException("Object already exists: " + objectName, e);
        } catch (IOException e) {
            throw new FileStorageException("Failed to store object " + objectName, e);
        }
    }

    @Override
    public InputStream open(String objectName) {
        try {
            return Files.newInputStream(resolve(objectName));
        } catch (NoSuchFileException e) {
            throw new FileContentNotFoundException(objectName);
        } catch (IOException e) {
            throw new FileStorageException("Failed to read object " + objectName, e);
        }
    }

    @Override
    public boolean delete(String objectName) {
        try {
            return Files.deleteIfExists(resolve(objectName));
        } catch (IOException e) {
            throw new FileStorageException("Failed to delete object " + objectName, e);
        }
    }

    @Override
    public int deleteByPrefix(String prefix) {
        Path directory = resolve(prefix);
        if (!Files.isDirectory(directory)) {
            return 0;
        }

        int deleted = 0;
        try (var walk = Files.walk(directory)) {
            // Depth-first so a directory is only removed once it is empty. Only files are counted,
            // so the number means the same thing as the GCS backend's: objects removed.
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                boolean isFile = Files.isRegularFile(path);
                Files.delete(path);
                if (isFile) {
                    deleted++;
                }
            }
        } catch (IOException | UncheckedIOException e) {
            throw new FileStorageException("Failed to delete objects under " + prefix
                    + " (deleted " + deleted + " before failing)", e);
        }

        log.info("Deleted {} object(s) under prefix {}", deleted, prefix);
        return deleted;
    }

    /**
     * Object names are generated, so traversal is not reachable from user input today. Checked
     * anyway, because the cost is one comparison and the thing being protected is every other
     * user's documents on the same disk.
     */
    private Path resolve(String objectName) {
        Path resolved = root.resolve(objectName).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Object name escapes the storage root: " + objectName);
        }
        return resolved;
    }
}
