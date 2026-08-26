package com.sails.ai.selfserviceapi.user.service;

import com.sails.ai.selfserviceapi.generated.model.CustomerPageResponse;
import com.sails.ai.selfserviceapi.generated.model.CustomerResponse;
import com.sails.ai.selfserviceapi.generated.model.UserStatus;
import com.sails.ai.selfserviceapi.user.entity.User;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.data.domain.Page;

public final class CustomerResponseMapper {

    private CustomerResponseMapper() {
    }

    public static CustomerResponse toResponse(User user) {
        return new CustomerResponse(user.getId(), user.getEmail(), user.getFirstName(), user.getLastName(),
                user.getCompanyName(), UserStatus.valueOf(user.getStatus().name()), user.getRoles(),
                toUtcOffset(user.getCreatedAt()))
                .jobTitle(user.getJobTitle())
                .country(user.getCountry())
                .trialStartDate(toUtcOffset(user.getTrialStartDate()))
                .trialEndDate(toUtcOffset(user.getTrialEndDate()));
    }

    public static CustomerPageResponse toPageResponse(Page<User> page) {
        return new CustomerPageResponse(
                page.getContent().stream().map(CustomerResponseMapper::toResponse).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }

    private static OffsetDateTime toUtcOffset(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
