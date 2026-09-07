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
                "--container=worker", "--image=img/worker:1", "--port=default",
                "--set-env-vars=^;^PLATFORM_API_URL=https://self-service-api.example.com;POC_SLUG=my-poc;PORT=9000");
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

    /**
     * A rollback re-parses the manifest stored with the target version, which never goes through
     * ManifestValidator — so a version built before the "ingress must declare a port" rule has
     * none. Falls back to Cloud Run's default rather than emitting the literal "--port=null".
     */
    @Test
    void fallsBackToCloudRunsDefaultPortWhenAStoredManifestsIngressDeclaresNone() {
        ManifestContainer api = new ManifestContainer("api", ContainerRole.INGRESS, "Dockerfile", ".", null, Map.of());
        ManifestContainer worker = new ManifestContainer("worker", ContainerRole.SIDECAR, "Dockerfile", ".", 9000, Map.of());
        PocManifest manifest = new PocManifest(List.of(api, worker), new Resources(null, null));
        Map<String, String> images = Map.of("api", "img/api:1", "worker", "img/worker:1");

        List<String> args = builder.buildContainerArgs("my-poc", manifest, images);

        assertThat(args).contains("--port=8080");
        assertThat(args).doesNotContain("--port=null");
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
                "--container=worker", "--image=img/worker:1", "--port=default",
                "--set-env-vars=^;^PLATFORM_API_URL=https://self-service-api.example.com;POC_SLUG=my-poc;PORT=9000");
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

        assertThat(blockFor("worker", args)).contains(
                "--set-env-vars=^;^PLATFORM_API_URL=https://self-service-api.example.com;POC_SLUG=my-poc;PORT=9000"
                        + ";SVC_CACHE_URL=http://localhost:9001");
    }

    /**
     * A deploy is a merge into the existing service, not a replacement. A service first deployed
     * by the earlier builder — which passed the sidecar's own declared port to gcloud — keeps that
     * port on the sidecar unless this explicitly clears it, and Cloud Run then rejects the whole
     * revision ("Revision template should contain exactly one container with an exposed port")
     * as soon as the ingress correctly gets one too. Observed on a real deploy, not hypothetical.
     */
    @Test
    void explicitlyUnsetsEachSidecarsPortSoAServiceDeployedWithThePortOnTheWrongContainerSelfHeals() {
        ManifestContainer api = new ManifestContainer("api", ContainerRole.INGRESS, "Dockerfile", ".", 8080, Map.of());
        ManifestContainer worker = new ManifestContainer("worker", ContainerRole.SIDECAR, "Dockerfile", ".", 9000, Map.of());
        PocManifest manifest = new PocManifest(List.of(api, worker), new Resources(null, null));
        Map<String, String> images = Map.of("api", "img/api:1", "worker", "img/worker:1");

        List<String> args = builder.buildContainerArgs("my-poc", manifest, images);

        assertThat(blockFor("worker", args)).contains("--port=default");
        assertThat(blockFor("api", args)).contains("--port=8080");
    }

    /**
     * Cloud Run injects PORT into the ingress container only, and every sidecar's port is
     * deliberately cleared so exactly one container exposes one — so without this injection a
     * sidecar has no way to learn the port the platform is simultaneously telling every other
     * container to reach it on. It cannot set PORT itself either: the name is reserved.
     */
    @Test
    void givesASidecarThePortItWasDeclaredWith() {
        ManifestContainer api = new ManifestContainer("api", ContainerRole.INGRESS, "Dockerfile", ".", 8080, Map.of());
        ManifestContainer worker = new ManifestContainer("worker", ContainerRole.SIDECAR, "Dockerfile", ".", 9000, Map.of());
        PocManifest manifest = new PocManifest(List.of(api, worker), new Resources(null, null));
        Map<String, String> images = Map.of("api", "img/api:1", "worker", "img/worker:1");

        List<String> args = builder.buildContainerArgs("my-poc", manifest, images);

        assertThat(envOf("worker", args)).containsEntry("PORT", "9000");
    }

    /**
     * The port a sidecar binds and the port everyone else is told to call it on are the same
     * number in the manifest, and are written in one place here so they cannot drift. If these
     * ever disagree, every call through SVC_&lt;NAME&gt;_URL fails with nothing pointing at the cause.
     */
    @Test
    void aSidecarsOwnPortMatchesTheAddressAdvertisedToEveryOtherContainer() {
        ManifestContainer api = new ManifestContainer("api", ContainerRole.INGRESS, "Dockerfile", ".", 8080, Map.of());
        ManifestContainer worker = new ManifestContainer("chat-worker", ContainerRole.SIDECAR, "Dockerfile", ".", 9000, Map.of());
        PocManifest manifest = new PocManifest(List.of(api, worker), new Resources(null, null));
        Map<String, String> images = Map.of("api", "img/api:1", "chat-worker", "img/worker:1");

        List<String> args = builder.buildContainerArgs("my-poc", manifest, images);

        String advertised = envOf("api", args).get("SVC_CHAT_WORKER_URL");
        String bound = envOf("chat-worker", args).get("PORT");
        assertThat(advertised).isEqualTo("http://localhost:" + bound);
    }

    /** The ingress binds Cloud Run's own $PORT, so the platform must not set one for it. */
    @Test
    void doesNotSetPortForTheIngressContainerWhichCloudRunInjectsItself() {
        ManifestContainer api = new ManifestContainer("api", ContainerRole.INGRESS, "Dockerfile", ".", 8080, Map.of());
        ManifestContainer worker = new ManifestContainer("worker", ContainerRole.SIDECAR, "Dockerfile", ".", 9000, Map.of());
        PocManifest manifest = new PocManifest(List.of(api, worker), new Resources(null, null));
        Map<String, String> images = Map.of("api", "img/api:1", "worker", "img/worker:1");

        List<String> args = builder.buildContainerArgs("my-poc", manifest, images);

        assertThat(envOf("api", args)).doesNotContainKey("PORT");
    }

    /** Parses one container's --set-env-vars back into a map, so assertions read by name. */
    private static Map<String, String> envOf(String containerName, List<String> args) {
        String flag = blockFor(containerName, args).stream()
                .filter(arg -> arg.startsWith("--set-env-vars="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no --set-env-vars for container '" + containerName + "'"));
        Map<String, String> env = new java.util.LinkedHashMap<>();
        for (String pair : flag.substring("--set-env-vars=^;^".length()).split(";")) {
            int eq = pair.indexOf('=');
            env.put(pair.substring(0, eq), pair.substring(eq + 1));
        }
        return env;
    }

    /** One container's flags run from its own --container= up to the next one's. */
    private static List<String> blockFor(String containerName, List<String> args) {
        int start = args.indexOf("--container=" + containerName);
        assertThat(start).isNotNegative();
        int end = start + 1;
        while (end < args.size() && !args.get(end).startsWith("--container=")) {
            end++;
        }
        return args.subList(start, end);
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
