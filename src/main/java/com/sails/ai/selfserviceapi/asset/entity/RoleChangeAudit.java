package com.sails.ai.selfserviceapi.asset.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Append-only audit trail for managed-role changes, written in the same transaction as the change. */
@Entity
@Table(name = "role_change_audit")
@Getter
@Setter
public class RoleChangeAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "actor_user_id", nullable = false, length = 36, updatable = false)
    private String actorUserId;

    @Column(name = "target_user_id", nullable = false, length = 36, updatable = false)
    private String targetUserId;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "before_roles", nullable = false, columnDefinition = "text[]", updatable = false)
    private List<String> beforeRoles = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "after_roles", nullable = false, columnDefinition = "text[]", updatable = false)
    private List<String> afterRoles = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
