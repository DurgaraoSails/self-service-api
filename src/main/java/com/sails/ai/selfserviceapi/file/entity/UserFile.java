package com.sails.ai.selfserviceapi.file.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * Metadata for one uploaded document. The bytes live in object storage; this row is what makes
 * them findable, quota-countable and purgeable.
 *
 * <p>{@code userId} and {@code pocId} are plain columns rather than JPA relations, matching
 * {@code ActivitySession} and {@code PocDeployment} — nothing here ever needs to navigate to the
 * user or the POC, and a relation would only invite a lazy-load on a listing query.
 */
@Entity
@Table(name = "user_files")
@Getter
@Setter
public class UserFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false, length = 36)
    private String userId;

    @Column(name = "poc_id", nullable = false, updatable = false)
    private Long pocId;

    /** Full object path in the bucket. Generated, never derived from the uploaded filename. */
    @Column(name = "object_name", nullable = false, updatable = false)
    private String objectName;

    @Column(name = "original_filename", nullable = false)
    private String originalFilename;

    /** Client-declared, and stored only to echo back on download. Validation sniffs the bytes. */
    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private Instant uploadedAt;

    /**
     * Soft delete, matching the {@code pocs} convention. The object itself is removed from the
     * bucket at the same time — this is not a recycle bin, it is a tombstone that keeps the row
     * available to purge accounting.
     */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
        if (uploadedAt == null) {
            uploadedAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
