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

/** Append-only, privacy-minimized telemetry. No raw search-query text is ever stored here. */
@Entity
@Table(name = "asset_events")
@Getter
@Setter
public class AssetEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "asset_id", updatable = false)
    private UUID assetId;

    @Column(name = "user_id", length = 36, updatable = false)
    private String userId;

    @Column(name = "event_type", nullable = false, length = 20, updatable = false)
    private String eventType;

    @Column(name = "search_session_id", updatable = false)
    private UUID searchSessionId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @PrePersist
    void onCreate() {
        occurredAt = Instant.now();
    }
}
