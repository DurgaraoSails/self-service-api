package com.sails.ai.selfserviceapi.file.storage;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import com.sails.ai.selfserviceapi.file.config.FileStorageProperties;
import com.sails.ai.selfserviceapi.file.exception.FileContentNotFoundException;
import com.sails.ai.selfserviceapi.file.exception.FileStorageException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** The production backend. Selected by {@code files.storage=gcs}. */
@Component
@ConditionalOnProperty(prefix = "files", name = "storage", havingValue = "gcs")
public class GcsFileStorage implements FileStorage {

    private static final Logger log = LoggerFactory.getLogger(GcsFileStorage.class);

    private final Storage storage;
    private final String bucket;

    public GcsFileStorage(Storage storage, FileStorageProperties properties) {
        if (properties.bucket() == null || properties.bucket().isBlank()) {
            // At startup rather than at the first upload. The alternative is a deploy that looks
            // healthy until a prospect tries to use it.
            throw new IllegalStateException("files.bucket is required when files.storage=gcs");
        }
        this.storage = storage;
        this.bucket = properties.bucket();
    }

    @Override
    public void store(String objectName, String contentType, InputStream content) {
        BlobInfo blobInfo = BlobInfo.newBuilder(BlobId.of(bucket, objectName))
                .setContentType(contentType)
                // Nothing user-uploaded may ever be interpreted by a browser as a document on
                // this bucket's origin, even if the bucket is somehow made readable.
                .setContentDisposition("attachment")
                .build();

        try {
            // A single request, not a resumable session: the per-file ceiling is an order of
            // magnitude below the client's buffer, so the upload either completes or restarts.
            // doesNotExist() turns a generated-id collision into a failure rather than a
            // silent overwrite of somebody's document.
            storage.createFrom(blobInfo, content, Storage.BlobWriteOption.doesNotExist());
        } catch (StorageException | IOException e) {
            throw new FileStorageException("Failed to store object " + objectName, e);
        }
    }

    @Override
    public InputStream open(String objectName) {
        Blob blob;
        try {
            blob = storage.get(BlobId.of(bucket, objectName));
        } catch (StorageException e) {
            throw new FileStorageException("Failed to read object " + objectName, e);
        }

        if (blob == null) {
            throw new FileContentNotFoundException(objectName);
        }
        return Channels.newInputStream(blob.reader());
    }

    @Override
    public boolean delete(String objectName) {
        try {
            return storage.delete(BlobId.of(bucket, objectName));
        } catch (StorageException e) {
            throw new FileStorageException("Failed to delete object " + objectName, e);
        }
    }

    @Override
    public int deleteByPrefix(String prefix) {
        int deleted = 0;
        try {
            // Deleted one at a time rather than batched, deliberately. Purge is expected to fail
            // partway through — a killed instance, a transient error — and per-object deletes
            // leave a consistent, smaller set behind that the next tick simply continues from.
            for (Blob blob : storage.list(bucket, Storage.BlobListOption.prefix(prefix)).iterateAll()) {
                if (storage.delete(blob.getBlobId())) {
                    deleted++;
                }
            }
        } catch (StorageException e) {
            throw new FileStorageException("Failed to delete objects under " + prefix
                    + " (deleted " + deleted + " before failing)", e);
        }

        log.info("Deleted {} object(s) under prefix {}", deleted, prefix);
        return deleted;
    }
}
