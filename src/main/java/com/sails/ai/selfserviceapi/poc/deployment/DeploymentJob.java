package com.sails.ai.selfserviceapi.poc.deployment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * A row on the hand-off queue the deploy pipeline claims from.
 *
 * Write-only from this side. Everything the pipeline needs is captured here at enqueue time so
 * it never reads the POC catalogue, and nothing in this service reads a job back — deployment
 * progress arrives over the status webhook instead, which keeps this service the single owner
 * of what a deployment means. The columns the pipeline manages (status, attempts, claim, error)
 * are deliberately absent from this mapping: they are none of this service's business.
 */
@Entity
@Table(name = "deployment_jobs")
@Getter
@Setter
public class DeploymentJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "deployment_id", nullable = false, updatable = false)
    private UUID deploymentId;

    @Column(name = "poc_slug", nullable = false, updatable = false)
    private String pocSlug;

    @Column(name = "github_url", updatable = false)
    private String githubUrl;

    @Column(name = "version_label", nullable = false, updatable = false)
    private String versionLabel;

    @Column(name = "container_image", updatable = false)
    private String containerImage;

    @Column(name = "kind", nullable = false, updatable = false, length = 20)
    private String kind;

    // Left to the database defaults — omitted from the INSERT rather than sent as null, and
    // never written from here, since the pipeline owns the row's lifecycle after the insert.
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;
}
