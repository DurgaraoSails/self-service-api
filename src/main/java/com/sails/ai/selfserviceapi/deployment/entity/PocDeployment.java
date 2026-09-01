package com.sails.ai.selfserviceapi.deployment.entity;

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

/** One row per tag-build-deploy attempt for a POC. */
@Entity
@Table(name = "poc_deployments")
@Getter
@Setter
public class PocDeployment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "poc_id", nullable = false, updatable = false)
    private Long pocId;

    @Column(name = "release_tag", nullable = false)
    private String releaseTag;

    // The commit main's HEAD pointed at when this deployment's tag was created — what
    // check-updates compares a fresh HEAD fetch against for the most recent active deployment.
    @Column(name = "commit_sha")
    private String commitSha;

    @Column(name = "cloud_build_id")
    private String cloudBuildId;

    @Column(name = "image_uri")
    private String imageUri;

    @Column(name = "status", nullable = false, length = 50)
    private String status = "building";

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "triggered_by")
    private String triggeredBy;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
        if (startedAt == null) {
            startedAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
