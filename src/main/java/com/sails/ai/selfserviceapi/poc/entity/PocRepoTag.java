package com.sails.ai.selfserviceapi.poc.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * One of the newest few tags GitHub reported for a POC's repository, as of the last refresh — a
 * child of {@link PocRepoStatus}. Replaced wholesale on every refresh (see
 * {@code PocRepoTagRepository.deleteByPocId}), never updated in place, matching
 * {@code PocVersionContainer}'s delete-then-insert convention for the same reason: idempotent under
 * a retried refresh, and simpler than diffing GitHub's list against what is already stored.
 */
@Entity
@Table(name = "poc_repo_tags")
@Getter
@Setter
public class PocRepoTag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "poc_id", nullable = false, updatable = false)
    private UUID pocId;

    @Column(name = "tag_name", nullable = false, updatable = false)
    private String tagName;

    @Column(name = "commit_sha", nullable = false, updatable = false)
    private String commitSha;

    /** commitSha equals the deploy branch's head at refresh time. */
    @Column(name = "is_current", nullable = false)
    private boolean current;

    /** GitHub's own newest-first order, preserved rather than re-sorted client-side. 0 is newest. */
    @Column(name = "position", nullable = false, updatable = false)
    private int position;
}
