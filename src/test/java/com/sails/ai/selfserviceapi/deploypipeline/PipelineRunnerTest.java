package com.sails.ai.selfserviceapi.deploypipeline;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sails.ai.selfserviceapi.deploypipeline.config.PipelineProperties;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubApiException;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubRepoRef;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubService;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ContainerRole;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestContainer;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.PocManifest;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.Resources;
import com.sails.ai.selfserviceapi.poc.service.PocDeploymentService;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PipelineRunnerTest {

    private static final UUID DEPLOYMENT_ID = UUID.randomUUID();
    private static final UUID POC_ID = UUID.randomUUID();
    private static final String GITHUB_URL = "https://github.com/SomeoneElse/their-public-poc";
    private static final GitHubRepoRef REPO = new GitHubRepoRef("SomeoneElse", "their-public-poc");

    private final GitHubService gitHubService = mock(GitHubService.class);
    private final PipelineExecutor executor = mock(PipelineExecutor.class);
    private final PocDeploymentService pocDeploymentService = mock(PocDeploymentService.class);

    private final PipelineRunner runner = new PipelineRunner(gitHubService, executor, pocDeploymentService,
            new PipelineProperties("cloud-build", "self-service-builder", "ghp_token", false, true,
                    Duration.ofMinutes(20), Duration.ofSeconds(10), null));

    /**
     * The point of checking push access up front is not a nicer message alone — it is that nothing
     * chargeable or externally visible happens for a repo this token could never finish deploying:
     * no tag, no Cloud Build submission, no images.
     */
    @Test
    void submitsNoBuildAndCreatesNoTagWhenTheTokenCannotPushToTheRepo() {
        when(gitHubService.parseRepoUrl(GITHUB_URL)).thenReturn(REPO);
        doThrow(new GitHubApiException("The configured GitHub token cannot push to " + REPO))
                .when(gitHubService).requirePushAccess(REPO);

        runner.runBuildAndDeploy(DEPLOYMENT_ID, POC_ID, "their-poc", GITHUB_URL, "1.0.0", "abc123", manifest());

        verify(gitHubService, never()).createTagIfAbsent(any(), anyString(), anyString());
        verifyNoInteractions(executor);
    }

    /** The same failure must still be recorded, or the deployment sits in-flight for ever. */
    @Test
    void recordsTheRefusalAgainstTheDeploymentSoItDoesNotLookStuck() {
        when(gitHubService.parseRepoUrl(GITHUB_URL)).thenReturn(REPO);
        doThrow(new GitHubApiException("The configured GitHub token cannot push to " + REPO))
                .when(gitHubService).requirePushAccess(REPO);

        runner.runBuildAndDeploy(DEPLOYMENT_ID, POC_ID, "their-poc", GITHUB_URL, "1.0.0", "abc123", manifest());

        verify(pocDeploymentService).reportStatus(eq(DEPLOYMENT_ID), eq("FAILED"), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.contains("cannot push to"));
    }

    private static PocManifest manifest() {
        return new PocManifest(
                List.of(new ManifestContainer("app", ContainerRole.INGRESS, "Dockerfile", ".", null, Map.of())),
                new Resources(null, null));
    }
}
