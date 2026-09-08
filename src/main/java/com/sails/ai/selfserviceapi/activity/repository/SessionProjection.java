package com.sails.ai.selfserviceapi.activity.repository;

import java.time.Instant;
import java.util.UUID;

public interface SessionProjection {

    UUID getPocId();

    String getPocName();

    Instant getStartedAt();

    Instant getLastSeenAt();

    Instant getEndedAt();

    Long getTotalSeconds();
}
