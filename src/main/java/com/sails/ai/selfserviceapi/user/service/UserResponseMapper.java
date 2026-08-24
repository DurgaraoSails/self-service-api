package com.sails.ai.selfserviceapi.user.service;

import com.sails.ai.selfserviceapi.generated.model.UserResponse;
import com.sails.ai.selfserviceapi.generated.model.UserStatus;
import com.sails.ai.selfserviceapi.user.entity.User;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public final class UserResponseMapper {

    private UserResponseMapper() {
    }

    public static UserResponse toResponse(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getFirstName(), user.getLastName(),
                UserStatus.valueOf(user.getStatus().name()))
                .displayName(user.getDisplayName())
                .roles(user.getRoles())
                .trialStartDate(toUtcOffset(user.getTrialStartDate()))
                .trialEndDate(toUtcOffset(user.getTrialEndDate()));
    }

    private static OffsetDateTime toUtcOffset(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
