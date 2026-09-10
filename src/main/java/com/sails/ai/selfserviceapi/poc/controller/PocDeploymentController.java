package com.sails.ai.selfserviceapi.poc.controller;

import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestContainer;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.PocManifest;
import com.sails.ai.selfserviceapi.generated.api.DeploymentApi;
import com.sails.ai.selfserviceapi.generated.model.DeployNewVersionRequest;
import com.sails.ai.selfserviceapi.generated.model.ManifestContainerRole;
import com.sails.ai.selfserviceapi.generated.model.PocDeploymentOverviewResponse;
import com.sails.ai.selfserviceapi.generated.model.PocDeploymentResponse;
import com.sails.ai.selfserviceapi.generated.model.PocManifestPreviewContainer;
import com.sails.ai.selfserviceapi.generated.model.PocManifestPreviewResponse;
import com.sails.ai.selfserviceapi.generated.model.PocRepoStatusResponse;
import com.sails.ai.selfserviceapi.generated.model.PocRepoTagResponse;
import com.sails.ai.selfserviceapi.generated.model.PocResponse;
import com.sails.ai.selfserviceapi.generated.model.PocSourceRepositoryResponse;
import com.sails.ai.selfserviceapi.generated.model.PocVersionResponse;
import com.sails.ai.selfserviceapi.generated.model.ReportDeploymentStatusRequest;
import com.sails.ai.selfserviceapi.generated.model.ReportUpstreamCommitsRequest;
import com.sails.ai.selfserviceapi.generated.model.UpstreamCommit;
import com.sails.ai.selfserviceapi.poc.config.DeploymentWebhookProperties;
import com.sails.ai.selfserviceapi.poc.entity.Poc;
import com.sails.ai.selfserviceapi.poc.entity.PocDeployment;
import com.sails.ai.selfserviceapi.poc.entity.PocRepoStatus;
import com.sails.ai.selfserviceapi.poc.entity.PocRepoTag;
import com.sails.ai.selfserviceapi.poc.entity.PocVersion;
import com.sails.ai.selfserviceapi.poc.entity.PocVersionContainer;
import com.sails.ai.selfserviceapi.poc.exception.InvalidWebhookSecretException;
import com.sails.ai.selfserviceapi.poc.service.PocDeploymentResponseMapper;
import com.sails.ai.selfserviceapi.poc.service.PocDeploymentService;
import com.sails.ai.selfserviceapi.poc.service.PocRepoStatusService;
import com.sails.ai.selfserviceapi.poc.service.PocResponseMapper;
import com.sails.ai.selfserviceapi.poc.service.PocService;
import com.sails.ai.selfserviceapi.security.CurrentUser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PocDeploymentController implements DeploymentApi {

    private final PocDeploymentService pocDeploymentService;
    private final PocService pocService;
    private final PocRepoStatusService pocRepoStatusService;
    private final DeploymentWebhookProperties webhookProperties;
    private final PocDeploymentResponseMapper mapper;

    public PocDeploymentController(PocDeploymentService pocDeploymentService, PocService pocService,
                                    PocRepoStatusService pocRepoStatusService, DeploymentWebhookProperties webhookProperties,
                                    PocDeploymentResponseMapper mapper) {
        this.pocDeploymentService = pocDeploymentService;
        this.pocService = pocService;
        this.pocRepoStatusService = pocRepoStatusService;
        this.webhookProperties = webhookProperties;
        this.mapper = mapper;
    }

    /**
     * With no {@code tag} (or no body at all): today's existing "derive and create" behaviour. With
     * one: deploys that already-existing tag directly — see
     * {@link PocDeploymentService#deployExistingTag} and
     * docs/specs/poc-tag-driven-deployment.md, "Three deploy paths, one pipeline."
     */
    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PocDeploymentResponse> deployNewVersion(UUID id, DeployNewVersionRequest deployNewVersionRequest) {
        String tag = deployNewVersionRequest == null ? null : deployNewVersionRequest.getTag();
        PocDeployment deployment = (tag == null || tag.isBlank())
                ? pocDeploymentService.deployNewVersion(id, CurrentUser.id())
                : pocDeploymentService.deployExistingTag(id, tag.trim(), CurrentUser.id());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(mapper.toDeploymentResponse(deployment, versionLabelOf(deployment)));
    }

    /**
     * One round trip off the database, no GitHub call — replaces separately fetching the POC,
     * GET .../versions, GET .../deployments and GET .../manifest-preview. See
     * docs/specs/poc-tag-driven-deployment.md, "API Surface."
     */
    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PocDeploymentOverviewResponse> getDeploymentOverview(UUID id) {
        Poc poc = pocService.getById(id);
        List<PocVersion> versions = pocDeploymentService.listVersions(id);
        Map<UUID, List<PocVersionContainer>> containersByVersionId = pocDeploymentService.containersByVersionId(
                versions.stream().map(PocVersion::getId).toList());

        List<PocVersionResponse> versionResponses = versions.stream()
                .map(version -> mapper.toVersionResponse(version, version.getId().equals(poc.getActiveVersionId()),
                        containersByVersionId.get(version.getId())))
                .toList();
        List<PocDeploymentResponse> deploymentResponses = pocDeploymentService.listDeployments(id).stream()
                .map(deployment -> mapper.toDeploymentResponse(deployment, versionLabelOf(deployment)))
                .toList();

        PocRepoStatus status = pocRepoStatusService.find(id).orElse(null);
        List<PocRepoTagResponse> tags = mergeTags(pocRepoStatusService.listTags(id), versions,
                status == null ? null : status.getHeadCommitSha());

        PocDeploymentOverviewResponse response = new PocDeploymentOverviewResponse(
                poc.getGithubUrl(), tags, versionResponses, deploymentResponses)
                .slug(poc.getSlug())
                .repoStatus(status == null ? null : toRepoStatusResponse(status));
        return ResponseEntity.ok(response);
    }

    /**
     * The recent repository tags plus every version this POC has deployed, deduped by tag name — a
     * version whose tag was later deleted from GitHub stays deployable (it still has an image) even
     * though a tag-only list would silently drop it. See docs/specs/poc-tag-driven-deployment.md,
     * "The list is a union, not just the tag list." Repository tags win where both exist: they carry
     * a real {@code isCurrent} (checked at refresh time against the live branch head), whereas a
     * version added only because it is not in the repository's tags cannot be currency-checked here
     * without another GitHub call.
     */
    private List<PocRepoTagResponse> mergeTags(List<PocRepoTag> repoTags, List<PocVersion> versions, String headCommitSha) {
        Map<String, PocRepoTagResponse> byName = new LinkedHashMap<>();
        for (PocRepoTag tag : repoTags) {
            byName.put(tag.getTagName(), new PocRepoTagResponse(tag.getTagName(), tag.isCurrent(), true)
                    .commitSha(tag.getCommitSha()));
        }
        for (PocVersion version : versions) {
            byName.computeIfAbsent(version.getVersionLabel(), name -> {
                String sha = version.getCommitSha();
                boolean current = sha != null && sha.equals(headCommitSha);
                return new PocRepoTagResponse(name, current, false).commitSha(sha);
            });
        }
        return new ArrayList<>(byName.values());
    }

    private PocRepoStatusResponse toRepoStatusResponse(PocRepoStatus status) {
        return new PocRepoStatusResponse(status.getCanCreateTags())
                .defaultBranch(status.getDefaultBranch())
                .deployBranch(status.getDeployBranch())
                .headCommitSha(status.getHeadCommitSha())
                .archived(status.getArchived())
                .visible(status.getVisible())
                .refreshedAt(status.getRefreshedAt() == null ? null : status.getRefreshedAt().atOffset(ZoneOffset.UTC))
                .refreshError(status.getRefreshError());
    }

    /**
     * Reads GitHub on this request's thread and only answers once the snapshot is written, so the
     * caller's next read of {@code deployment-overview} is guaranteed to see it.
     *
     * <p>Deliberately synchronous, unlike the refreshes triggered by POC creation and by a finished
     * deploy. Those are side-effects of something else the admin was doing, so making them wait
     * would be gratuitous. This one *is* the thing the admin asked for: dispatching it to the async
     * pool and answering 202 meant the refetch that follows raced the GitHub read and almost always
     * won, leaving the page showing the same "last refreshed" value it had before — a button that
     * visibly did nothing. Four GitHub calls is a second or so, which is what the spinner is for.
     *
     * <p>404s on a missing POC before doing any of that — {@code pocService.getById} throws.
     */
    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> refreshPocRepoStatus(UUID id) {
        Poc poc = pocService.getById(id);
        pocRepoStatusService.refreshNow(poc);
        return ResponseEntity.noContent().build();
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<PocVersionResponse>> getPocVersions(UUID id) {
        Poc poc = pocService.getById(id);
        List<PocVersion> versions = pocDeploymentService.listVersions(id);
        Map<UUID, List<PocVersionContainer>> containersByVersionId = pocDeploymentService.containersByVersionId(
                versions.stream().map(PocVersion::getId).toList());

        List<PocVersionResponse> responses = versions.stream()
                .map(version -> mapper.toVersionResponse(version, version.getId().equals(poc.getActiveVersionId()),
                        containersByVersionId.get(version.getId())))
                .toList();
        return ResponseEntity.ok(responses);
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PocDeploymentResponse> redeployPocVersion(UUID id, UUID versionId) {
        PocDeployment deployment = pocDeploymentService.redeployVersion(id, versionId, CurrentUser.id());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(mapper.toDeploymentResponse(deployment, versionLabelOf(deployment)));
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<PocDeploymentResponse>> getPocDeployments(UUID id) {
        List<PocDeploymentResponse> deployments = pocDeploymentService.listDeployments(id).stream()
                .map(deployment -> mapper.toDeploymentResponse(deployment, versionLabelOf(deployment)))
                .toList();
        return ResponseEntity.ok(deployments);
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PocDeploymentResponse> getPocDeploymentById(UUID deploymentId) {
        PocDeployment deployment = pocDeploymentService.getDeploymentById(deploymentId);
        return ResponseEntity.ok(mapper.toDeploymentResponse(deployment, versionLabelOf(deployment)));
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PocDeploymentResponse> retryDeployment(UUID deploymentId) {
        PocDeployment retry = pocDeploymentService.retryDeployment(deploymentId, CurrentUser.id());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(mapper.toDeploymentResponse(retry, versionLabelOf(retry)));
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PocManifestPreviewResponse> getManifestPreview(UUID id) {
        PocManifest manifest = pocDeploymentService.previewManifest(id);
        List<PocManifestPreviewContainer> containers = manifest.containers().stream()
                .map(this::toPreviewContainer)
                .toList();
        return ResponseEntity.ok(new PocManifestPreviewResponse(containers));
    }

    private PocManifestPreviewContainer toPreviewContainer(ManifestContainer container) {
        return new PocManifestPreviewContainer(container.name(), ManifestContainerRole.fromValue(container.role().name()))
                .port(container.port());
    }

    @Override
    public ResponseEntity<PocDeploymentResponse> reportDeploymentStatus(UUID deploymentId, String xPipelineWebhookSecret,
                                                                          ReportDeploymentStatusRequest reportDeploymentStatusRequest) {
        if (!MessageDigest.isEqual(
                xPipelineWebhookSecret.getBytes(StandardCharsets.UTF_8),
                webhookProperties.webhookSecret().getBytes(StandardCharsets.UTF_8))) {
            throw new InvalidWebhookSecretException();
        }

        PocDeployment deployment = pocDeploymentService.reportStatus(
                deploymentId,
                reportDeploymentStatusRequest.getStatus().getValue(),
                reportDeploymentStatusRequest.getContainerImage(),
                reportDeploymentStatusRequest.getCommitSha(),
                reportDeploymentStatusRequest.getHostedUrl(),
                reportDeploymentStatusRequest.getLogsUrl(),
                reportDeploymentStatusRequest.getErrorMessage()
        );
        return ResponseEntity.ok(mapper.toDeploymentResponse(deployment, versionLabelOf(deployment)));
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<PocResponse>> getPocsWithDeploymentIssues() {
        List<Poc> deployable = pocService.listDeployable();
        Map<UUID, String> latestStatuses = pocDeploymentService.latestDeploymentStatuses(
                deployable.stream().map(Poc::getId).toList());

        List<Poc> withIssues = deployable.stream()
                .filter(poc -> needsAttention(latestStatuses.get(poc.getId())))
                .toList();

        Map<UUID, String> activeVersionLabels = pocDeploymentService.activeVersionLabels(
                withIssues.stream().map(Poc::getActiveVersionId).filter(Objects::nonNull).toList());

        List<PocResponse> responses = withIssues.stream()
                .map(poc -> {
                    UUID activeVersionId = poc.getActiveVersionId();
                    String activeVersionLabel = activeVersionId != null ? activeVersionLabels.get(activeVersionId) : null;
                    return PocResponseMapper.toResponse(poc, activeVersionLabel, latestStatuses.get(poc.getId()));
                })
                .toList();
        return ResponseEntity.ok(responses);
    }

    /** Never deployed, or the last attempt didn't succeed. Not BUILDING/DEPLOYING — those are still in flight. */
    private static final Set<String> ISSUE_STATUSES = Set.of("FAILED", "SKIPPED");

    private boolean needsAttention(String latestStatus) {
        return latestStatus == null || ISSUE_STATUSES.contains(latestStatus);
    }

    @Override
    public ResponseEntity<List<PocSourceRepositoryResponse>> getSourceRepositories(String xPipelineWebhookSecret) {
        requirePipelineSecret(xPipelineWebhookSecret);

        List<PocSourceRepositoryResponse> repositories = pocService.listSourceRepositories().stream()
                .map(poc -> new PocSourceRepositoryResponse(poc.getId(), poc.getGithubUrl()).slug(poc.getSlug()))
                .toList();
        return ResponseEntity.ok(repositories);
    }

    @Override
    public ResponseEntity<Void> reportUpstreamCommits(String xPipelineWebhookSecret,
                                                        ReportUpstreamCommitsRequest reportUpstreamCommitsRequest) {
        requirePipelineSecret(xPipelineWebhookSecret);

        Map<UUID, String> commitsByPocId = reportUpstreamCommitsRequest.getCommits().stream()
                .collect(Collectors.toMap(UpstreamCommit::getPocId, UpstreamCommit::getCommitSha, (first, second) -> second));
        pocService.recordUpstreamCommits(commitsByPocId);
        return ResponseEntity.noContent().build();
    }

    /** Constant-time comparison — a timing-based probe must not be able to recover the secret. */
    private void requirePipelineSecret(String provided) {
        if (provided == null || !MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8),
                webhookProperties.webhookSecret().getBytes(StandardCharsets.UTF_8))) {
            throw new InvalidWebhookSecretException();
        }
    }

    private String versionLabelOf(PocDeployment deployment) {
        return pocDeploymentService.versionLabel(deployment.getPocVersionId());
    }
}
