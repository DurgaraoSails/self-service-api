package com.sails.ai.selfserviceapi.deploypipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sails.ai.selfserviceapi.deploypipeline.build.ProcessRunner;
import com.sails.ai.selfserviceapi.deploypipeline.config.GcpProperties;
import com.sails.ai.selfserviceapi.deploypipeline.config.PipelineProperties;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ContainerRole;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestContainer;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.PocManifest;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.Resources;
import com.sails.ai.selfserviceapi.deploypipeline.run.CloudRunDeployCommandBuilder;
import com.sails.ai.selfserviceapi.deploypipeline.run.CloudRunService;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * gcloud parses every flag after the first {@code --container=} as scoped to that container —
 * region/project/service-account/allow-unauthenticated must all come before it, or gcloud rejects
 * the command with a usage error (exit code 2). {@code BuildService.deployStep} (the Cloud Build
 * executor) already gets this right; this covers the local executor, which drives the exact same
 * gcloud command from a subprocess.
 */
class LocalPipelineExecutorTest {

    private final ProcessRunner processRunner = mock(ProcessRunner.class);
    private final CloudRunService cloudRunService = mock(CloudRunService.class);
    private final GcpProperties gcp = new GcpProperties("proj", "us-central1", "prod");
    private final PipelineProperties properties = new PipelineProperties(
            "local", null, null, null, false, true, null, Duration.ofMinutes(10), Duration.ofMinutes(10), Duration.ofSeconds(5));

    private final LocalPipelineExecutor executor = new LocalPipelineExecutor(
            processRunner, gcp, properties, cloudRunService, new CloudRunDeployCommandBuilder());

    @Test
    void putsEveryServiceLevelFlagBeforeTheFirstContainerFlag() {
        ManifestContainer web = new ManifestContainer("web", ContainerRole.INGRESS, "Dockerfile", ".", 8080, Map.of());
        ManifestContainer api = new ManifestContainer("api", ContainerRole.SIDECAR, "Dockerfile", "api", 8081, Map.of());
        PocManifest manifest = new PocManifest(List.of(web, api), new Resources(null, null));
        when(cloudRunService.getServiceUrl("my-poc")).thenReturn("https://my-poc-abc.run.app");

        executor.deploy("my-poc", manifest, Map.of("web", "img/web:1", "api", "img/api:1"));

        ArgumentCaptor<String[]> commandCaptor = ArgumentCaptor.forClass(String[].class);
        verify(processRunner).run(isNull(), any(Duration.class), commandCaptor.capture());
        List<String> args = List.of(commandCaptor.getValue());

        int firstContainerFlagIndex = args.indexOf("--container=web");
        assertThat(firstContainerFlagIndex).isPositive();
        List<String> beforeFirstContainer = args.subList(0, firstContainerFlagIndex);
        assertThat(beforeFirstContainer).contains(
                "--region=us-central1", "--service-account=poc-runtime-prod@proj.iam.gserviceaccount.com",
                "--allow-unauthenticated");
    }
}
