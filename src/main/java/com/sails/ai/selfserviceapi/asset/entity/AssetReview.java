package com.sails.ai.selfserviceapi.asset.entity;

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

/** Append-only: a decision is never edited once recorded. */
@Entity
@Table(name = "asset_reviews")
@Getter
@Setter
public class AssetReview {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "revision_id", nullable = false, updatable = false)
    private UUID revisionId;

    @Column(name = "reviewer_user_id", nullable = false, length = 36, updatable = false)
    private String reviewerUserId;

    @Column(name = "decision", nullable = false, length = 20, updatable = false)
    private String decision;

    @Column(name = "feedback", updatable = false)
    private String feedback;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
