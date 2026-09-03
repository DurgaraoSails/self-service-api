package com.sails.ai.selfserviceapi.deploypipeline.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.sails.ai.selfserviceapi.deploypipeline.config.GcpProperties;
import com.sails.ai.selfserviceapi.deploypipeline.config.PipelineProperties;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ContainerRole;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestContainer;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.PocManifest;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.Platform;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.Resources;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.Scaling;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Asserts the exact arg sequence built for `gcloud run deploy`. --container, --depends-on and
 * --startup-probe are verified-real flags (checked against an installed `gcloud run deploy
 * --help` while this was written) — this test is what keeps that verification from rotting as
 * the builder evolves.
 */
class CloudRunDeployCommandBuilderTest {

    private final CloudRunDeployCommandBuilder builder = new CloudRunDeployCommandBuilder();
    private final GcpProperties gcp = new GcpProperties("sails-agenthub", "us-central1", "dev");
    private final PipelineProperties properties = new PipelineProperties(
            "cloud-build", "self-service-builder", "", "", false, false, "",
            Duration.ofMinutes(20), Duration.ofMinutes(20), Duration.ofSeconds(10));

    @Test
    void singleContainerManifestProducesTheSameShapeAsTodaysDefault() {
        ManifestContainer app = new ManifestContainer(
                "app", ContainerRole.INGRESS, "Dockerfile", ".", null, "/healthz", null, Map.of());
        PocManifest manifest = new PocManifest(PocManifest.API_VERSION, null, null, null,
                List.of(app), Resources.defaults(), Scaling.defaults(), Platform.none());
        Map<String, String> images = Map.of("app", "us-central1-docker.pkg.dev/sails-agenthub/poc-images/dummy-poc/app:1.0.1");

        List<String> args = builder.buildDeployArgs("dummy-poc", "1.0.1", manifest, images, gcp, properties);

        assertThat(args).containsExactly(
                "run", "deploy", "dummy-poc",
                "--region=us-central1",
                "--service-account=poc-runtime-dev@sails-agenthub.iam.gserviceaccount.com",
                "--no-allow-unauthenticated",
                "--min-instances=0",
                "--max-instances=3",
                "--quiet",
                "--container=app",
                "--image=us-central1-docker.pkg.dev/sails-agenthub/poc-images/dummy-poc/app:1.0.1",
                "--port=8080",
                "--cpu=1",
                "--memory=512Mi",
                "--set-env-vars=POC_SLUG=dummy-poc,POC_VERSION=1.0.1,BASE_PATH=");
    }

    @Test
    void multiContainerManifestAddsDependsOnStartupProbeAndSvcUrlsForEverySidecar() {
        ManifestContainer web = new ManifestContainer(
                "web", ContainerRole.INGRESS, "apps/web/Dockerfile", "apps/web", null, "/healthz", null, Map.of());
        ManifestContainer api = new ManifestContainer(
                "api", ContainerRole.SIDECAR, "apps/api/Dockerfile", "apps/api", 8081, "/healthz", null, Map.of());
        ManifestContainer agent = new ManifestContainer(
                "agent-writer", ContainerRole.SIDECAR, "apps/agent/Dockerfile", "apps/agent", 8082, "/live", null, Map.of());
        PocManifest manifest = new PocManifest(PocManifest.API_VERSION, "Research Assistant Suite", null, null,
                List.of(web, api, agent), new Resources("2", "2Gi"), new Scaling(0, 5), Platform.none());

        Map<String, String> images = Map.of(
                "web", "us-central1-docker.pkg.dev/sails-agenthub/poc-images/research/web:1.0.4",
                "api", "us-central1-docker.pkg.dev/sails-agenthub/poc-images/research/api:1.0.4",
                "agent-writer", "us-central1-docker.pkg.dev/sails-agenthub/poc-images/research/agent-writer:1.0.4");

        List<String> args = builder.buildDeployArgs("research", "1.0.4", manifest, images, gcp, properties);

        // Top-level flags, then the ingress block, then each sidecar block in manifest order.
        assertThat(args).startsWith(
                "run", "deploy", "research",
                "--region=us-central1",
                "--service-account=poc-runtime-dev@sails-agenthub.iam.gserviceaccount.com",
                "--no-allow-unauthenticated",
                "--min-instances=0",
                "--max-instances=5",
                "--quiet",
                "--container=web",
                "--image=us-central1-docker.pkg.dev/sails-agenthub/poc-images/research/web:1.0.4",
                "--port=8080",
                "--cpu=2",
                "--memory=2Gi",
                "--depends-on=api,agent-writer");

        int envIndex = args.indexOf("--container=web") + 6;
        assertThat(args.get(envIndex)).isEqualTo("--set-env-vars=POC_SLUG=research,POC_VERSION=1.0.4,BASE_PATH=,"
                + "SVC_API_URL=http://localhost:8081,SVC_AGENT_WRITER_URL=http://localhost:8082");

        assertThat(args).contains(
                "--container=api",
                "--image=us-central1-docker.pkg.dev/sails-agenthub/poc-images/research/api:1.0.4",
                "--startup-probe=httpGet.port=8081,httpGet.path=/healthz",
                "--container=agent-writer",
                "--image=us-central1-docker.pkg.dev/sails-agenthub/poc-images/research/agent-writer:1.0.4",
                "--startup-probe=httpGet.port=8082,httpGet.path=/live");

        // Sidecars never get --cpu/--memory of their own (Cloud Run's own default, per the
        // resource-allocation decision), and never a --depends-on (only the ingress declares one).
        assertThat(args).filteredOn(a -> a.startsWith("--cpu=") || a.startsWith("--memory=")).hasSize(2);
        assertThat(args).filteredOn(a -> a.startsWith("--depends-on=")).hasSize(1);
    }

    @Test
    void allowUnauthenticatedFlipsTheTopLevelFlag() {
        PocManifest manifest = PocManifest.defaultSingleContainer();
        PipelineProperties publicProps = new PipelineProperties(
                "cloud-build", "self-service-builder", "", "", false, true, "",
                Duration.ofMinutes(20), Duration.ofMinutes(20), Duration.ofSeconds(10));

        List<String> args = builder.buildDeployArgs("dummy-poc", "1.0.1", manifest,
                Map.of("app", "image:1.0.1"), gcp, publicProps);

        assertThat(args).contains("--allow-unauthenticated").doesNotContain("--no-allow-unauthenticated");
    }
}
