package com.sails.ai.selfserviceapi.activity.repository;

public interface UserUsageProjection {

    String getUserId();

    String getFirstName();

    String getLastName();

    String getEmail();

    Long getTotalSeconds();
}
