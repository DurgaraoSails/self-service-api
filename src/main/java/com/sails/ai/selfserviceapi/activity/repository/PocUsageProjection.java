package com.sails.ai.selfserviceapi.activity.repository;

import java.util.UUID;

public interface PocUsageProjection {

    UUID getPocId();

    String getPocName();

    Long getTotalSeconds();

    Long getUserCount();
}
