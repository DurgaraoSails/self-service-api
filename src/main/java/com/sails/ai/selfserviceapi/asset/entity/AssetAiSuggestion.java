package com.sails.ai.selfserviceapi.asset.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "asset_ai_suggestions")
@Getter
@Setter
public class AssetAiSuggestion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "revision_id", nullable = false, updatable = false)
    private UUID revisionId;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "suggested_title", length = 200)
    private String suggestedTitle;

    @Column(name = "suggested_summary")
    private String suggestedSummary;

    /** JSON array of strings, serialized the same way {@code PocDeployment.containerProgress} is. */
    @Column(name = "suggested_tags")
    private String suggestedTags;

    @Column(name = "provider", length = 100)
    private String provider;

    @Column(name = "model", length = 100)
    private String model;

    @Column(name = "schema_version", length = 20)
    private String schemaVersion;

    @Column(name = "input_checksum", length = 64)
    private String inputChecksum;

    @Column(name = "error_code", length = 100)
    private String errorCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
