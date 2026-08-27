package com.sails.ai.selfserviceapi.activity.repository;

import java.time.Instant;

public interface SessionProjection {

    Long getPocId();

    String getPocName();

    Instant getStartedAt();

    Instant getLastSeenAt();

    Instant getEndedAt();

    Long getTotalSeconds();
}
