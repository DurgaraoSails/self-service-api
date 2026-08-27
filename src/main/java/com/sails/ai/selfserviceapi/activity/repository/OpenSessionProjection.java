package com.sails.ai.selfserviceapi.activity.repository;

import java.time.Instant;

public interface OpenSessionProjection {

    String getUserId();

    String getFirstName();

    String getLastName();

    Long getPocId();

    String getPocName();

    Instant getStartedAt();

    Instant getLastSeenAt();
}
