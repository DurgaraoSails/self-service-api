package com.sails.ai.selfserviceapi.poc.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * A POC's repository snapshot, as of the last explicit refresh — never fetched from GitHub on a
 * page load. See docs/specs/poc-tag-driven-deployment.md. One row per POC, created (or replaced)
 * by {@code PocRepoStatusService.refresh}, never by anything on the read path.
 */
@Entity
@Table(name = "poc_repo_status")
@Getter
@Setter
public class PocRepoStatus {

    @Id
    @Column(name = "poc_id", nullable = false, updatable = false)
    private UUID pocId;

    @Column(name = "default_branch")
    private String defaultBranch;

    /** The branch actually used, after a POC's own deployBranch and pipeline.deploy-branch are applied. */
    @Column(name = "deploy_branch")
    private String deployBranch;

    @Column(name = "head_commit_sha")
    private String headCommitSha;

    /** From GitHub's permissions.push — a UI hint only; the pipeline re-checks at deploy time. */
    @Column(name = "can_create_tags")
    private Boolean canCreateTags;

    @Column(name = "is_archived")
    private Boolean archived;

    /** False covers both a 404 and a private repo the token cannot see — see GitHubService.RepoAccess.NOT_FOUND. */
    @Column(name = "is_visible")
    private Boolean visible;

    @Column(name = "refreshed_at")
    private Instant refreshedAt;

    /** Null on success. Set instead of clearing the rest of the row, so a failed refresh doesn't look like a repo with no tags. */
    @Column(name = "refresh_error")
    private String refreshError;
}
