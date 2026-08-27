package com.sails.ai.selfserviceapi.activity.repository;

public interface PocUsageProjection {

    Long getPocId();

    String getPocName();

    Long getTotalSeconds();

    Long getUserCount();
}
