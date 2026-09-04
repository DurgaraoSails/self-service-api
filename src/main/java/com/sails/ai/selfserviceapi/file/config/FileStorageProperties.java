package com.sails.ai.selfserviceapi.file.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Where user files are stored and how much of them a user may store. See
 * docs/specs/file-management.md.
 */
@ConfigurationProperties(prefix = "files")
public record FileStorageProperties(

        /**
         * gcs — the real bucket. Production, and the only backend that survives a Cloud Run
         *       instance being replaced.
         * local — writes under {@link #localDir()} on this machine instead. Exists for the same
         *       reason {@code pipeline.executor=skip} does: the feature stays runnable by a
         *       developer with no GCP credentials at all, rather than failing at startup.
         */
        String storage,

        /** gcs only. Its own bucket, not a prefix in the pipeline's — retention and access are
         *  this bucket's whole purpose and are nothing like the build artifacts it would share. */
        String bucket,

        /** local only. Blank uses a {@code self-service-files} directory under the system temp. */
        String localDir,

        /**
         * Per-file ceiling. Kept well under Cloud Run's 32 MiB request limit so the multipart
         * spool threshold can sit above it and uploads never touch the (memory-backed) filesystem.
         */
        DataSize maxFileSize,

        /** Per-(user, POC) file count. */
        int maxFilesPerPoc,

        /** Per-user total across every POC they have trialled. */
        DataSize maxBytesPerUser,

        /**
         * Content types accepted on upload, validated against magic bytes rather than the
         * declared header. Configuration rather than a constant so the set can be widened without
         * a release — but every addition widens what POC parsers must survive, so widening it is
         * a decision, not a default.
         */
        List<String> allowedContentTypes
) {

    public boolean isGcs() {
        return "gcs".equalsIgnoreCase(storage);
    }

    public long maxFileSizeBytes() {
        return maxFileSize.toBytes();
    }

    public long maxBytesPerUserBytes() {
        return maxBytesPerUser.toBytes();
    }
}
