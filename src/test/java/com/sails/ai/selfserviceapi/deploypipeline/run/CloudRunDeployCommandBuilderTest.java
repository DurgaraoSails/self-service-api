package com.sails.ai.selfserviceapi.deploypipeline.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.sails.ai.selfserviceapi.deploypipeline.config.PipelineProperties;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ContainerRole;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestContainer;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.PocManifest;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.Resources;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CloudRunDeployCommandBuilderTest {

    private final PipelineProperties properties = new PipelineProperties(
            "local", null, null, null, false, true, null, Duration.ofMinutes(10), Duration.ofMinutes(10), Duration.ofSeconds(5),
            "https://self-service-api.example.com");

    private final CloudRunDeployCommandBuilder builder = new CloudRunDeployCommandBuilder(properties);

    @Test
    void aSingleDefaultContainerProducesTheSamePlainFormAsBeforeManifestSupportPlusPlatformEnv() {
        // Deliberately NOT --container=app: a real regression (gcloud rejected --container= with
        // exit code 2 — a usage error) proved this must stay the exact plain form every
        // pre-manifest, single-container POC already deployed with.
        ManifestContainer app = new ManifestContainer("app", ContainerRole.INGRESS, "Dockerfile", ".", null, Map.of());
        PocManifest manifest = new PocManifest(List.of(app), new Resources(null, null));

        List<String> args = builder.buildContainerArgs("my-poc", manifest, Map.of("app", "registry/proj/poc-images/slug/app:1.0.1"));

        assertThat(args).containsExactly(
                "--image=registry/proj/poc-images/slug/app:1.0.1",
                "--set-env-vars=^;^PLATFORM_API_URL=https://self-service-api.example.com;POC_SLUG=my-poc");
    }

    @Test
    void aSingleContainerWithResourcesAppliesThemDirectlyWithoutContainerScoping() {
        ManifestContainer app = new ManifestContainer("app", ContainerRole.INGRESS, "Dockerfile", ".", null, Map.of());
        PocManifest manifest = new PocManifest(List.of(app), new Resources("2", "1Gi"));

        List<String> args = builder.buildContainerArgs("my-poc", manifest, Map.of("app", "img/app:1"));

        assertThat(args).containsExactly("--image=img/app:1", "--cpu=2", "--memory=1Gi",
                "--set-env-vars=^;^PLATFORM_API_URL=https://self-service-api.example.com;POC_SLUG=my-poc");
    }

    @Test
    void producesOneContainerBlockPerManifestContainerInOrder() {
        ManifestContainer api = new ManifestContainer("api", ContainerRole.INGRESS, "Dockerfile", ".", 8080, Map.of());
        ManifestContainer worker = new ManifestContainer("worker", ContainerRole.SIDECAR, "worker/Dockerfile", "worker", 9000, Map.of());
        PocManifest manifest = new PocManifest(List.of(api, worker), new Resources(null, null));
        Map<String, String> images = Map.of("api", "img/api:1", "worker", "img/worker:1");

        List<String> args = builder.buildContainerArgs("my-poc", manifest, images);

        assertThat(args).containsExactly(
                "--container=api", "--image=img/api:1", "--port=8080",
                "--set-env-vars=^;^PLATFORM_API_URL=https://self-service-api.example.com;POC_SLUG=my-poc;SVC_WORKER_URL=http://localhost:9000",
                "--container=worker", "--image=img/worker:1",
                "--set-env-vars=^;^PLATFORM_API_URL=https://self-service-api.example.com;POC_SLUG=my-poc");
    }

    /**
     * Cloud Run's own rule: "only one container can have the port exposed." A sidecar's declared
     * port exists for the platform's SVC_<NAME>_URL injection — it must never reach gcloud as
     * this container's --port, or Cloud Run would see two ports declared for one service.
     */
    @Test
    void neverEmitsPortForASidecarEvenThoughItDeclaresOne() {
        ManifestContainer api = new ManifestContainer("api", ContainerRole.INGRESS, "Dockerfile", ".", 8080, Map.of());
        ManifestContainer worker = new ManifestContainer("worker", ContainerRole.SIDECAR, "Dockerfile", ".", 9000, Map.of());
        PocManifest manifest = new PocManifest(List.of(api, worker), new Resources(null, null));
        Map<String, String> images = Map.of("api", "img/api:1", "worker", "img/worker:1");

        List<String> args = builder.buildContainerArgs("my-poc", manifest, images);

        assertThat(args).doesNotContain("--port=9000");
    }

    @Test
    void resourcesApplyOnlyToTheIngressContainer() {
        ManifestContainer api = new ManifestContainer("api", ContainerRole.INGRESS, "Dockerfile", ".", 8080, Map.of());
        ManifestContainer worker = new ManifestContainer("worker", ContainerRole.SIDECAR, "Dockerfile", ".", 9000, Map.of());
        PocManifest manifest = new PocManifest(List.of(api, worker), new Resources("2", "1Gi"));
        Map<String, String> images = Map.of("api", "img/api:1", "worker", "img/worker:1");

        List<String> args = builder.buildContainerArgs("my-poc", manifest, images);

        assertThat(args).containsExactly(
                "--container=api", "--image=img/api:1", "--port=8080", "--cpu=2", "--memory=1Gi",
                "--set-env-vars=^;^PLATFORM_API_URL=https://self-service-api.example.com;POC_SLUG=my-poc;SVC_WORKER_URL=http://localhost:9000",
                "--container=worker", "--image=img/worker:1",
                "--set-env-vars=^;^PLATFORM_API_URL=https://self-service-api.example.com;POC_SLUG=my-poc");
    }

    @Test
    void aContainerWithEnvVarsGetsASetEnvVarsFlagCombiningManifestAndPlatformEnv() {
        ManifestContainer api = new ManifestContainer("api", ContainerRole.INGRESS, "Dockerfile", ".", null, Map.of("LOG_LEVEL", "info"));
        PocManifest manifest = new PocManifest(List.of(api), new Resources(null, null));

        List<String> args = builder.buildContainerArgs("my-poc", manifest, Map.of("api", "img/api:1"));

        assertThat(args).contains(
                "--set-env-vars=^;^LOG_LEVEL=info;PLATFORM_API_URL=https://self-service-api.example.com;POC_SLUG=my-poc");
    }

    @Test
    void aSidecarGetsNoSvcUrlForItselfOnlyForOtherSidecars() {
        ManifestContainer api = new ManifestContainer("api", ContainerRole.INGRESS, "Dockerfile", ".", 8080, Map.of());
        ManifestContainer worker = new ManifestContainer("worker", ContainerRole.SIDECAR, "Dockerfile", ".", 9000, Map.of());
        ManifestContainer cache = new ManifestContainer("cache", ContainerRole.SIDECAR, "Dockerfile", ".", 9001, Map.of());
        PocManifest manifest = new PocManifest(List.of(api, worker, cache), new Resources(null, null));
        Map<String, String> images = Map.of("api", "img/api:1", "worker", "img/worker:1", "cache", "img/cache:1");

        List<String> args = builder.buildContainerArgs("my-poc", manifest, images);

        int workerEnvIndex = args.indexOf("--container=worker") + 2;
        assertThat(args.get(workerEnvIndex)).isEqualTo(
                "--set-env-vars=^;^PLATFORM_API_URL=https://self-service-api.example.com;POC_SLUG=my-poc;SVC_CACHE_URL=http://localhost:9001");
    }

    @Test
    void throwsWhenAManifestContainerHasNoBuiltImage() {
        ManifestContainer api = new ManifestContainer("api", ContainerRole.INGRESS, "Dockerfile", ".", null, Map.of());
        PocManifest manifest = new PocManifest(List.of(api), new Resources(null, null));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> builder.buildContainerArgs("my-poc", manifest, Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("api");
    }
}
