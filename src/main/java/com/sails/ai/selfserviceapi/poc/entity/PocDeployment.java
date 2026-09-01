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
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "poc_deployments")
@Getter
@Setter
public class PocDeployment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "poc_id", nullable = false, updatable = false)
    private Long pocId;

    @Column(name = "poc_version_id", nullable = false, updatable = false)
    private Long pocVersionId;

    @Column(name = "kind", nullable = false, length = 20, updatable = false)
    private String kind;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "PENDING";

    @Column(name = "logs_url", length = 500)
    private String logsUrl;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Column(name = "initiated_by", length = 36, updatable = false)
    private String initiatedBy;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        startedAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
