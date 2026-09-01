package com.sails.ai.selfserviceapi.poc.service;

import com.sails.ai.selfserviceapi.generated.model.PocResponse;
import com.sails.ai.selfserviceapi.generated.model.PocSummaryResponse;
import com.sails.ai.selfserviceapi.poc.entity.Poc;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public final class PocResponseMapper {

    private PocResponseMapper() {
    }

    public static PocSummaryResponse toSummaryResponse(Poc poc) {
        return new PocSummaryResponse(poc.getId(), poc.getName(), poc.getDescription(),
                PocSummaryResponse.StatusEnum.fromValue(poc.getStatus()))
                .iconUrl(poc.getIconUrl())
                .version(poc.getVersion())
                .owner(poc.getOwner())
                .category(poc.getCategory())
                .technologies(poc.getTechnologies())
                .demoType(poc.getDemoType())
                .details(poc.getDetails())
                .guideSteps(poc.getGuideSteps())
                .deletedAt(toUtcOffset(poc.getDeletedAt()));
    }

    public static PocResponse toResponse(Poc poc) {
        return new PocResponse(poc.getId(), poc.getName(), poc.getDescription(),
                PocResponse.StatusEnum.fromValue(poc.getStatus()))
                .iconUrl(poc.getIconUrl())
                .version(poc.getVersion())
                .owner(poc.getOwner())
                .category(poc.getCategory())
                .technologies(poc.getTechnologies())
                .demoType(poc.getDemoType())
                .details(poc.getDetails())
                .guideSteps(poc.getGuideSteps())
                .deletedAt(toUtcOffset(poc.getDeletedAt()))
                .appUrl(poc.getAppUrl())
                .githubUrl(poc.getGithubUrl())
                .containerImage(poc.getContainerImage())
                .slug(poc.getSlug())
                .deploymentStatus(PocResponse.DeploymentStatusEnum.fromValue(poc.getDeploymentStatus()))
                .currentReleaseTag(poc.getCurrentReleaseTag());
    }

    private static OffsetDateTime toUtcOffset(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
