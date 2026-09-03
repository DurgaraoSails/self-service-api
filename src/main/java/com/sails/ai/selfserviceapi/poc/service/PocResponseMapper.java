package com.sails.ai.selfserviceapi.poc.service;

import com.sails.ai.selfserviceapi.generated.model.PocCategoryResponse;
import com.sails.ai.selfserviceapi.generated.model.PocResponse;
import com.sails.ai.selfserviceapi.generated.model.PocSummaryResponse;
import com.sails.ai.selfserviceapi.poc.entity.Poc;
import com.sails.ai.selfserviceapi.poc.entity.PocCategory;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public final class PocResponseMapper {

    private PocResponseMapper() {
    }

    public static PocSummaryResponse toSummaryResponse(Poc poc, String activeVersionLabel, String latestDeploymentStatus) {
        return new PocSummaryResponse(poc.getId(), poc.getName(), poc.getDescription(),
                PocSummaryResponse.VisibilityStatusEnum.fromValue(poc.getVisibilityStatus()))
                .iconUrl(poc.getIconUrl())
                .activeVersion(activeVersionLabel)
                .latestDeploymentStatus(latestDeploymentStatus != null
                        ? PocSummaryResponse.LatestDeploymentStatusEnum.fromValue(latestDeploymentStatus)
                        : null)
                .owner(poc.getOwner())
                .category(poc.getCategory())
                .technologies(poc.getTechnologies())
                .demoType(poc.getDemoType())
                .details(poc.getDetails())
                .guideSteps(poc.getGuideSteps())
                .deletedAt(toUtcOffset(poc.getDeletedAt()));
    }

    public static PocResponse toResponse(Poc poc, String activeVersionLabel, String latestDeploymentStatus) {
        return new PocResponse(poc.getId(), poc.getName(), poc.getDescription(),
                PocResponse.VisibilityStatusEnum.fromValue(poc.getVisibilityStatus()))
                .iconUrl(poc.getIconUrl())
                .activeVersion(activeVersionLabel)
                .latestDeploymentStatus(latestDeploymentStatus != null
                        ? PocResponse.LatestDeploymentStatusEnum.fromValue(latestDeploymentStatus)
                        : null)
                .owner(poc.getOwner())
                .category(poc.getCategory())
                .technologies(poc.getTechnologies())
                .demoType(poc.getDemoType())
                .details(poc.getDetails())
                .guideSteps(poc.getGuideSteps())
                .deletedAt(toUtcOffset(poc.getDeletedAt()))
                .appUrl(poc.getAppUrl())
                .githubUrl(poc.getGithubUrl())
                .activeVersionId(poc.getActiveVersionId())
                .slug(poc.getSlug());
    }

    public static PocCategoryResponse toCategoryResponse(PocCategory category) {
        return new PocCategoryResponse(category.getId(), category.getName());
    }

    private static OffsetDateTime toUtcOffset(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
