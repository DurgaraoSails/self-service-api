package com.sails.ai.selfserviceapi.poc.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "poc_versions")
@Getter
@Setter
public class PocVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "poc_id", nullable = false, updatable = false)
    private UUID pocId;

    /**
     * Populated only when {@link #versionLabel} parses as semver, and used only for ordering — a
     * tag like {@code release-2024} or {@code latest} has no major/minor/patch to store. Boxed
     * rather than primitive so "not semver" is representable at all.
     */
    @Column(name = "major", updatable = false)
    private Integer major;

    @Column(name = "minor", updatable = false)
    private Integer minor;

    @Column(name = "patch", updatable = false)
    private Integer patch;

    /**
     * The identity: a git tag name, not a number this platform invents — either one it derived
     * (e.g. {@code 1.0.7}) or one an admin picked from the repository's existing tags (e.g.
     * {@code v2.3.0}, {@code release-2024}). See docs/specs/poc-tag-driven-deployment.md.
     */
    @Column(name = "version_label", nullable = false, updatable = false, length = 255)
    private String versionLabel;

    @Column(name = "container_image", length = 500)
    private String containerImage;

    /** Whether containerImage still resolves in Artifact Registry, as of the last refresh. Null means never checked. */
    @Column(name = "image_available")
    private Boolean imageAvailable;

    @Column(name = "image_checked_at")
    private Instant imageCheckedAt;

    /** Commit this version was built from — reported by the pipeline, used for update detection. */
    @Column(name = "commit_sha")
    private String commitSha;

    /**
     * The manifest exactly as built — raw poc.yaml text, or null for a repo with no poc.yaml.
     * Redeploy parses this, never a fresh GitHub read, so a repo's poc.yaml changing after this
     * version was built can never change what a later rollback to it deploys.
     */
    @Column(name = "manifest_yaml")
    private String manifestYaml;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
