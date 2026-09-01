package com.sails.ai.selfserviceapi.deployment.service;

import com.sails.ai.selfserviceapi.deployment.entity.PocDeployment;
import com.sails.ai.selfserviceapi.generated.model.PocDeploymentResponse;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public final class PocDeploymentResponseMapper {

    private PocDeploymentResponseMapper() {
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
