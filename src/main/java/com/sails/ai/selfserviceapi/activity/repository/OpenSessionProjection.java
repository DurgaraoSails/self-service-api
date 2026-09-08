package com.sails.ai.selfserviceapi.activity.repository;

import java.time.Instant;
import java.util.UUID;

public interface OpenSessionProjection {

    String getUserId();

    String getFirstName();

    String getLastName();

    UUID getPocId();

    String getPocName();

    Instant getStartedAt();

    Instant getLastSeenAt();
}
