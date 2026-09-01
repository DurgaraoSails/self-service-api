package com.sails.ai.selfserviceapi.poc.service;

import com.sails.ai.selfserviceapi.generated.model.DeploymentKind;
import com.sails.ai.selfserviceapi.generated.model.DeploymentStatus;
import com.sails.ai.selfserviceapi.generated.model.PocDeploymentResponse;
import com.sails.ai.selfserviceapi.generated.model.PocVersionResponse;
import com.sails.ai.selfserviceapi.poc.entity.PocDeployment;
import com.sails.ai.selfserviceapi.poc.entity.PocVersion;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public final class PocDeploymentResponseMapper {

    private PocDeploymentResponseMapper() {
    }

    public static PocVersionResponse toVersionResponse(PocVersion version, boolean isActive) {
        return new PocVersionResponse(version.getId(), version.getPocId(), version.getVersionLabel(),
                version.getMajor(), version.getMinor(), version.getPatch(), isActive, toUtcOffset(version.getCreatedAt()))
                .containerImage(version.getContainerImage());
    }

    public static PocDeploymentResponse toDeploymentResponse(PocDeployment deployment, String versionLabel) {
        return new PocDeploymentResponse(deployment.getId(), deployment.getPocId(), deployment.getPocVersionId(),
                versionLabel, DeploymentKind.fromValue(deployment.getKind()), DeploymentStatus.fromValue(deployment.getStatus()),
                toUtcOffset(deployment.getStartedAt()))
                .logsUrl(deployment.getLogsUrl())
                .errorMessage(deployment.getErrorMessage())
                .initiatedBy(deployment.getInitiatedBy())
                .completedAt(toUtcOffset(deployment.getCompletedAt()));
    }

    private static OffsetDateTime toUtcOffset(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
