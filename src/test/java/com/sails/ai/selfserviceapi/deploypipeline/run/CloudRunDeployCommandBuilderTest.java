package com.sails.ai.selfserviceapi.deploypipeline.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.sails.ai.selfserviceapi.deploypipeline.manifest.ContainerRole;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestContainer;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.PocManifest;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.Resources;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CloudRunDeployCommandBuilderTest {

    private final CloudRunDeployCommandBuilder builder = new CloudRunDeployCommandBuilder();

    @Test
    void aSingleDefaultContainerProducesTheSamePlainFormAsBeforeManifestSupport() {
        // Deliberately NOT --container=app: a real regression (gcloud rejected --container= with
        // exit code 2 — a usage error) proved this must stay the exact plain form every
        // pre-manifest, single-container POC already deployed with.
        ManifestContainer app = new ManifestContainer("app", ContainerRole.INGRESS, "Dockerfile", ".", null, Map.of());
        PocManifest manifest = new PocManifest(List.of(app), new Resources(null, null));

        List<String> args = builder.buildContainerArgs(manifest, Map.of("app", "registry/proj/poc-images/slug/app:1.0.1"));

        assertThat(args).containsExactly("--image=registry/proj/poc-images/slug/app:1.0.1");
    }

    @Test
    void aSingleContainerWithResourcesAppliesThemDirectlyWithoutContainerScoping() {
        ManifestContainer app = new ManifestContainer("app", ContainerRole.INGRESS, "Dockerfile", ".", null, Map.of());
        PocManifest manifest = new PocManifest(List.of(app), new Resources("2", "1Gi"));

        List<String> args = builder.buildContainerArgs(manifest, Map.of("app", "img/app:1"));

        assertThat(args).containsExactly("--image=img/app:1", "--cpu=2", "--memory=1Gi");
    }

    @Test
    void producesOneContainerBlockPerManifestContainerInOrder() {
        ManifestContainer api = new ManifestContainer("api", ContainerRole.INGRESS, "Dockerfile", ".", null, Map.of());
        ManifestContainer worker = new ManifestContainer("worker", ContainerRole.SIDECAR, "Dockerfile", "worker", 9000, Map.of());
        PocManifest manifest = new PocManifest(List.of(api, worker), new Resources(null, null));
        Map<String, String> images = Map.of("api", "img/api:1", "worker", "img/worker:1");

        List<String> args = builder.buildContainerArgs(manifest, images);

        assertThat(args).containsExactly(
                "--container=api", "--image=img/api:1",
                "--container=worker", "--image=img/worker:1", "--port=9000");
    }

    @Test
    void resourcesApplyOnlyToTheIngressContainer() {
        ManifestContainer api = new ManifestContainer("api", ContainerRole.INGRESS, "Dockerfile", ".", null, Map.of());
        ManifestContainer worker = new ManifestContainer("worker", ContainerRole.SIDECAR, "Dockerfile", ".", 9000, Map.of());
        PocManifest manifest = new PocManifest(List.of(api, worker), new Resources("2", "1Gi"));
        Map<String, String> images = Map.of("api", "img/api:1", "worker", "img/worker:1");

        List<String> args = builder.buildContainerArgs(manifest, images);

        assertThat(args).containsExactly(
                "--container=api", "--image=img/api:1", "--cpu=2", "--memory=1Gi",
                "--container=worker", "--image=img/worker:1", "--port=9000");
    }

    @Test
    void aContainerWithEnvVarsGetsASetEnvVarsFlagUsingTheAlternateDelimiter() {
        ManifestContainer api = new ManifestContainer("api", ContainerRole.INGRESS, "Dockerfile", ".", null, Map.of("LOG_LEVEL", "info"));
        PocManifest manifest = new PocManifest(List.of(api), new Resources(null, null));

        List<String> args = builder.buildContainerArgs(manifest, Map.of("api", "img/api:1"));

        assertThat(args).contains("--set-env-vars=^;^LOG_LEVEL=info");
    }

    @Test
    void throwsWhenAManifestContainerHasNoBuiltImage() {
        ManifestContainer api = new ManifestContainer("api", ContainerRole.INGRESS, "Dockerfile", ".", null, Map.of());
        PocManifest manifest = new PocManifest(List.of(api), new Resources(null, null));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> builder.buildContainerArgs(manifest, Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("api");
    }
}
