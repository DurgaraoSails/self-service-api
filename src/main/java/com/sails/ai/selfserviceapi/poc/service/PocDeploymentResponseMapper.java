package com.sails.ai.selfserviceapi.poc.service;

import com.sails.ai.selfserviceapi.generated.model.DeploymentKind;
import com.sails.ai.selfserviceapi.generated.model.DeploymentStatus;
import com.sails.ai.selfserviceapi.generated.model.ManifestContainerRole;
import com.sails.ai.selfserviceapi.generated.model.PocDeploymentContainerStatus;
import com.sails.ai.selfserviceapi.generated.model.PocDeploymentResponse;
import com.sails.ai.selfserviceapi.generated.model.PocVersionContainerResponse;
import com.sails.ai.selfserviceapi.generated.model.PocVersionResponse;
import com.sails.ai.selfserviceapi.poc.entity.PocDeployment;
import com.sails.ai.selfserviceapi.poc.entity.PocVersion;
import com.sails.ai.selfserviceapi.poc.entity.PocVersionContainer;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * A Spring component rather than a static utility (as before manifest support) because reading
 * back a deployment's {@code containerProgress} JSON needs an {@link ObjectMapper} — matches the
 * same injected-ObjectMapper pattern already used by {@code TrialExpiredAccessDeniedHandler}.
 */
@Component
public class PocDeploymentResponseMapper {

    private final ObjectMapper objectMapper;

    public PocDeploymentResponseMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public PocVersionResponse toVersionResponse(PocVersion version, boolean isActive, List<PocVersionContainer> containers) {
        PocVersionResponse response = new PocVersionResponse(version.getId(), version.getPocId(), version.getVersionLabel(),
                version.getMajor(), version.getMinor(), version.getPatch(), isActive, toUtcOffset(version.getCreatedAt()))
                .containerImage(version.getContainerImage());
        if (containers != null && !containers.isEmpty()) {
            response.containers(containers.stream().map(this::toVersionContainerResponse).toList());
        }
        return response;
    }

    public PocDeploymentResponse toDeploymentResponse(PocDeployment deployment, String versionLabel) {
        PocDeploymentResponse response = new PocDeploymentResponse(deployment.getId(), deployment.getPocId(), deployment.getPocVersionId(),
                versionLabel, DeploymentKind.fromValue(deployment.getKind()), DeploymentStatus.fromValue(deployment.getStatus()),
                toUtcOffset(deployment.getStartedAt()))
                .logsUrl(deployment.getLogsUrl())
                .errorMessage(deployment.getErrorMessage())
                .initiatedBy(deployment.getInitiatedBy())
                .completedAt(toUtcOffset(deployment.getCompletedAt()));

        List<PocDeploymentContainerStatus> containers = parseContainerProgress(deployment.getContainerProgress());
        if (containers != null) {
            response.containers(containers);
        }
        return response;
    }

    private PocVersionContainerResponse toVersionContainerResponse(PocVersionContainer container) {
        return new PocVersionContainerResponse(container.getName(), ManifestContainerRole.fromValue(container.getRole()))
                .containerImage(container.getContainerImage())
                .port(container.getPort());
    }

    private List<PocDeploymentContainerStatus> parseContainerProgress(String containerProgressJson) {
        if (containerProgressJson == null || containerProgressJson.isBlank()) {
            return null;
        }
        List<ContainerProgress> entries = objectMapper.readValue(containerProgressJson, new TypeReference<List<ContainerProgress>>() {
        });
        return entries.stream()
                .map(entry -> new PocDeploymentContainerStatus(entry.name(), ManifestContainerRole.fromValue(entry.role()),
                        PocDeploymentContainerStatus.StateEnum.fromValue(entry.state()))
                        .containerImage(entry.containerImage())
                        .port(entry.port()))
                .toList();
    }

    private static OffsetDateTime toUtcOffset(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
