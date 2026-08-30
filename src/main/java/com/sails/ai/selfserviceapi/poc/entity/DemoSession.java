package com.sails.ai.selfserviceapi.poc.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "demo_sessions")
@Getter
@Setter
public class DemoSession {
    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "poc_id", nullable = false)
    private Long pocId;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(nullable = false)
    private String status;

    @Column(name = "access_token")
    private String accessToken;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_at")
    private Instant createdAt;

    // getters/setters
}