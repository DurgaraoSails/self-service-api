package com.sails.ai.selfserviceapi.poc.service;

import com.sails.ai.selfserviceapi.generated.model.DeploymentMode;
import com.sails.ai.selfserviceapi.generated.model.PocCategoryResponse;
import com.sails.ai.selfserviceapi.generated.model.PocResponse;
import com.sails.ai.selfserviceapi.generated.model.PocSummaryResponse;
import com.sails.ai.selfserviceapi.generated.model.PocType;
import com.sails.ai.selfserviceapi.poc.entity.Poc;
import com.sails.ai.selfserviceapi.poc.entity.PocCategory;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public final class PocResponseMapper {

    private PocResponseMapper() {
    }

    public static PocSummaryResponse toSummaryResponse(Poc poc, String activeVersionLabel, String latestDeploymentStatus) {
        return new PocSummaryResponse(poc.getId(), poc.getName(), poc.getDescription(), hasActiveUrl(poc),
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
                .deletedAt(toUtcOffset(poc.getDeletedAt()))
                .pocType(PocType.fromValue(poc.getPocType()));
    }

    public static PocResponse toResponse(Poc poc, String activeVersionLabel, String latestDeploymentStatus) {
        return new PocResponse(poc.getId(), poc.getName(), poc.getDescription(), hasActiveUrl(poc),
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
                .deployBranch(poc.getDeployBranch())
                .activeVersionId(poc.getActiveVersionId())
                .slug(poc.getSlug())
                .deploymentMode(DeploymentMode.fromValue(poc.getDeploymentMode()))
                .pocType(PocType.fromValue(poc.getPocType()));
    }

    public static PocCategoryResponse toCategoryResponse(PocCategory category) {
        return new PocCategoryResponse(category.getId(), category.getName());
    }

    /**
     * True the moment a POC has something to launch — an AUTOMATIC-mode POC only once a deploy has
     * actually written appUrl, a SELF-mode one as soon as its Cloud Run URL is saved. Deliberately
     * not "activeVersion != null": that never gets set for SELF mode, which allocates no version at
     * all, so it would leave every SELF-mode POC looking permanently undeployed on the public
     * catalog no matter how long its URL has been live.
     */
    private static boolean hasActiveUrl(Poc poc) {
        return poc.getAppUrl() != null && !poc.getAppUrl().isBlank();
    }

    private static OffsetDateTime toUtcOffset(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
