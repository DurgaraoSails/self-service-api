package com.sails.ai.selfserviceapi.poc.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "pocs")
@Getter
@Setter
public class Poc {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "description", nullable = false, length = 2000)
    private String description;

    @Column(name = "icon_url", length = 500)
    private String iconUrl;

    @Column(name = "app_url", length = 500)
    private String appUrl;

    @Column(name = "github_url", length = 500)
    private String githubUrl;

    @Column(name = "owner", length = 200)
    private String owner;

    @Column(name = "category", length = 100)
    private String category;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "technologies", nullable = false, columnDefinition = "text[]")
    private List<String> technologies = new ArrayList<>();

    @Column(name = "demo_type", length = 50)
    private String demoType;

    @Column(name = "status", nullable = false, length = 50)
    private String status = "ACTIVE";

    @Column(name = "details")
    private String details;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "guide_steps", nullable = false, columnDefinition = "text[]")
    private List<String> guideSteps = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "active_version_id")
    private Long activeVersionId;

    /** Addressable name for the deploy target — Cloud Run service name and image path segment. */
    @Column(name = "slug")
    private String slug;

    // Written by the deploy pipeline, which holds the GitHub credential — never by this service.
    // Compared against the active version's commitSha to answer "is main ahead of what's live?".
    @Column(name = "latest_main_commit_sha")
    private String latestMainCommitSha;

    @Column(name = "latest_main_checked_at")
    private Instant latestMainCheckedAt;

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
