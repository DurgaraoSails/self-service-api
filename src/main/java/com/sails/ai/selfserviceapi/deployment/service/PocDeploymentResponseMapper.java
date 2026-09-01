package com.sails.ai.selfserviceapi.deployment.service;

import com.sails.ai.selfserviceapi.deployment.entity.PocDeployment;
import com.sails.ai.selfserviceapi.deployment.service.DeploymentQueryService.PocWithLatestDeployment;
import com.sails.ai.selfserviceapi.generated.model.PocDeploymentResponse;
import com.sails.ai.selfserviceapi.generated.model.PocDeploymentSummaryResponse;
import com.sails.ai.selfserviceapi.poc.entity.Poc;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public final class PocDeploymentResponseMapper {

    private PocDeploymentResponseMapper() {
    }

    public static PocDeploymentSummaryResponse toSummaryResponse(PocWithLatestDeployment row) {
        Poc poc = row.poc();
        PocDeploymentSummaryResponse response = new PocDeploymentSummaryResponse(
                poc.getId(),
                poc.getName(),
                PocDeploymentSummaryResponse.DeploymentStatusEnum.fromValue(poc.getDeploymentStatus()))
                .slug(poc.getSlug())
                .currentReleaseTag(poc.getCurrentReleaseTag());

        return row.latestDeployment() == null
                ? response
                : response.latestDeployment(toResponse(row.latestDeployment()));
    }

    public static PocDeploymentResponse toResponse(PocDeployment deployment) {
        return new PocDeploymentResponse(
                deployment.getId(),
                deployment.getReleaseTag(),
                PocDeploymentResponse.StatusEnum.fromValue(deployment.getStatus()),
                toUtcOffset(deployment.getStartedAt()))
                .cloudBuildId(deployment.getCloudBuildId())
                .imageUri(deployment.getImageUri())
                .failureReason(deployment.getFailureReason())
                .triggeredBy(deployment.getTriggeredBy())
                .finishedAt(toUtcOffset(deployment.getFinishedAt()));
    }

    private static OffsetDateTime toUtcOffset(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
