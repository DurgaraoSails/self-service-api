package com.sails.ai.selfserviceapi.poc.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "poc_versions")
@Getter
@Setter
public class PocVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "poc_id", nullable = false, updatable = false)
    private Long pocId;

    @Column(name = "major", nullable = false, updatable = false)
    private int major;

    @Column(name = "minor", nullable = false, updatable = false)
    private int minor;

    @Column(name = "patch", nullable = false, updatable = false)
    private int patch;

    @Column(name = "version_label", nullable = false, updatable = false, length = 20)
    private String versionLabel;

    @Column(name = "container_image", length = 500)
    private String containerImage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
