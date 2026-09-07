package com.sails.ai.selfserviceapi.deploypipeline.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.sails.ai.selfserviceapi.deploypipeline.config.PocRuntimeProperties;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ContainerRole;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestContainer;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.PocManifest;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.Resources;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CloudRunDeployCommandBuilderTest {

    private final PocRuntimeProperties pocRuntime = new PocRuntimeProperties(
            8080, "https://self-service-api.example.com", "https://portal.example.com");

    private final CloudRunDeployCommandBuilder builder = new CloudRunDeployCommandBuilder(pocRuntime);

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
                "--set-env-vars=^;^PLATFORM_API_URL=https://self-service-api.example.com;POC_SLUG=my-poc;PORTAL_ORIGIN=https://portal.example.com");
    }

    @Test
    void aSingleContainerWithResourcesAppliesThemDirectlyWithoutContainerScoping() {
        ManifestContainer app = new ManifestContainer("app", ContainerRole.INGRESS, "Dockerfile", ".", null, Map.of());
        PocManifest manifest = new PocManifest(List.of(app), new Resources("2", "1Gi"));

        List<String> args = builder.buildContainerArgs("my-poc", manifest, Map.of("app", "img/app:1"));

        assertThat(args).containsExactly("--image=img/app:1", "--cpu=2", "--memory=1Gi",
                "--set-env-vars=^;^PLATFORM_API_URL=https://self-service-api.example.com;POC_SLUG=my-poc;PORTAL_ORIGIN=https://portal.example.com");
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
                "--set-env-vars=^;^PLATFORM_API_URL=https://self-service-api.example.com;POC_SLUG=my-poc;PORTAL_ORIGIN=https://portal.example.com;SVC_WORKER_URL=http://localhost:9000",
                "--container=worker", "--image=img/worker:1",
                "--set-env-vars=^;^PLATFORM_API_URL=https://self-service-api.example.com;POC_SLUG=my-poc;PORTAL_ORIGIN=https://portal.example.com;PORT=9000");
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
                "--set-env-vars=^;^PLATFORM_API_URL=https://self-service-api.example.com;POC_SLUG=my-poc;PORTAL_ORIGIN=https://portal.example.com;SVC_WORKER_URL=http://localhost:9000",
                "--container=worker", "--image=img/worker:1",
                "--set-env-vars=^;^PLATFORM_API_URL=https://self-service-api.example.com;POC_SLUG=my-poc;PORTAL_ORIGIN=https://portal.example.com;PORT=9000");
    }

    @Test
    void aContainerWithEnvVarsGetsASetEnvVarsFlagCombiningManifestAndPlatformEnv() {
        ManifestContainer api = new ManifestContainer("api", ContainerRole.INGRESS, "Dockerfile", ".", null, Map.of("LOG_LEVEL", "info"));
        PocManifest manifest = new PocManifest(List.of(api), new Resources(null, null));

        List<String> args = builder.buildContainerArgs("my-poc", manifest, Map.of("api", "img/api:1"));

        assertThat(args).contains(
                "--set-env-vars=^;^LOG_LEVEL=info;PLATFORM_API_URL=https://self-service-api.example.com;POC_SLUG=my-poc;PORTAL_ORIGIN=https://portal.example.com");
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
                "--set-env-vars=^;^PLATFORM_API_URL=https://self-service-api.example.com;POC_SLUG=my-poc;PORTAL_ORIGIN=https://portal.example.com;PORT=9000"
                        + ";SVC_CACHE_URL=http://localhost:9001");
    }

    /**
     * gcloud's own check, run before it sends anything: "Invalid value for [--container]: Exactly
     * one container must specify --port or --use-http2". *Carrying* the flag is what counts, so a
     * sidecar must not be given one at all — not even gcloud's documented "unset" value
     * `--port=default`, which an earlier revision emitted to clear a stale port and which failed
     * every deploy at exit 1 without ever reaching the API. Observed on a real deploy, not
     * hypothetical: build 953f34be against poc-testbed-one 1.0.9.
     */
    @Test
    void givesTheSidecarNoPortFlagAtAllSinceGcloudCountsCarryingItAsSpecifyingAPort() {
        ManifestContainer api = new ManifestContainer("api", ContainerRole.INGRESS, "Dockerfile", ".", 8080, Map.of());
        ManifestContainer worker = new ManifestContainer("worker", ContainerRole.SIDECAR, "Dockerfile", ".", 9000, Map.of());
        PocManifest manifest = new PocManifest(List.of(api, worker), new Resources(null, null));
        Map<String, String> images = Map.of("api", "img/api:1", "worker", "img/worker:1");

        List<String> args = builder.buildContainerArgs("my-poc", manifest, images);

        assertThat(blockFor("worker", args)).noneMatch(arg -> arg.startsWith("--port="));
        assertThat(blockFor("api", args)).contains("--port=8080");
        // The whole command, not just one block: exactly one --port anywhere is the rule.
        assertThat(args).filteredOn(arg -> arg.startsWith("--port=")).hasSize(1);
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

    /**
     * PORTAL_ORIGIN is both the postMessage targetOrigin a POC replies to and the value it puts in
     * its own frame-ancestors, so it has to come from the platform rather than from anything an
     * embedder controls.
     */
    @Test
    void givesEveryContainerThePortalOriginAllowedToFrameIt() {
        ManifestContainer api = new ManifestContainer("api", ContainerRole.INGRESS, "Dockerfile", ".", 8080, Map.of());
        ManifestContainer worker = new ManifestContainer("worker", ContainerRole.SIDECAR, "Dockerfile", ".", 9000, Map.of());
        PocManifest manifest = new PocManifest(List.of(api, worker), new Resources(null, null));
        Map<String, String> images = Map.of("api", "img/api:1", "worker", "img/worker:1");

        List<String> args = builder.buildContainerArgs("my-poc", manifest, images);

        assertThat(envOf("api", args)).containsEntry("PORTAL_ORIGIN", "https://portal.example.com");
        assertThat(envOf("worker", args)).containsEntry("PORTAL_ORIGIN", "https://portal.example.com");
    }

    /** Nothing to inject beats injecting the empty string, which a POC would read as a real origin. */
    @Test
    void omitsPortalOriginEntirelyWhenNoneIsConfigured() {
        CloudRunDeployCommandBuilder noOrigin = new CloudRunDeployCommandBuilder(
                new PocRuntimeProperties(8080, "https://self-service-api.example.com", ""));
        ManifestContainer app = new ManifestContainer("app", ContainerRole.INGRESS, "Dockerfile", ".", null, Map.of());
        PocManifest manifest = new PocManifest(List.of(app), new Resources(null, null));

        List<String> args = noOrigin.buildContainerArgs("my-poc", manifest, Map.of("app", "img/app:1"));

        assertThat(args.toString()).doesNotContain("PORTAL_ORIGIN");
    }

    /** The platform owns the ingress port; a manifest that names none gets the configured one. */
    @Test
    void usesTheConfiguredIngressPortWhenTheManifestDeclaresNone() {
        CloudRunDeployCommandBuilder onPort9090 = new CloudRunDeployCommandBuilder(
                new PocRuntimeProperties(9090, "https://self-service-api.example.com", "https://portal.example.com"));
        ManifestContainer api = new ManifestContainer("api", ContainerRole.INGRESS, "Dockerfile", ".", null, Map.of());
        ManifestContainer worker = new ManifestContainer("worker", ContainerRole.SIDECAR, "Dockerfile", ".", 9000, Map.of());
        PocManifest manifest = new PocManifest(List.of(api, worker), new Resources(null, null));

        List<String> args = onPort9090.buildContainerArgs("my-poc", manifest,
                Map.of("api", "img/api:1", "worker", "img/worker:1"));

        assertThat(blockFor("api", args)).contains("--port=9090");
    }

    /** A manifest that names its own ingress port still wins — poc-platform-sdk's schema allows one. */
    @Test
    void prefersTheManifestsOwnIngressPortOverTheConfiguredDefault() {
        CloudRunDeployCommandBuilder onPort9090 = new CloudRunDeployCommandBuilder(
                new PocRuntimeProperties(9090, "https://self-service-api.example.com", "https://portal.example.com"));
        ManifestContainer api = new ManifestContainer("api", ContainerRole.INGRESS, "Dockerfile", ".", 3000, Map.of());
        ManifestContainer worker = new ManifestContainer("worker", ContainerRole.SIDECAR, "Dockerfile", ".", 9000, Map.of());
        PocManifest manifest = new PocManifest(List.of(api, worker), new Resources(null, null));

        List<String> args = onPort9090.buildContainerArgs("my-poc", manifest,
                Map.of("api", "img/api:1", "worker", "img/worker:1"));

        assertThat(blockFor("api", args)).contains("--port=3000");
    }

    /** A probe targets the port that container actually listens on, which differs per role. */
    @Test
    void turnsEachContainersHealthPathIntoAStartupProbeOnItsOwnPort() {
        ManifestContainer api = new ManifestContainer("api", ContainerRole.INGRESS, "Dockerfile", ".", 8080, Map.of(), "/healthz");
        ManifestContainer worker = new ManifestContainer("worker", ContainerRole.SIDECAR, "Dockerfile", ".", 9000, Map.of(), "/ready");
        PocManifest manifest = new PocManifest(List.of(api, worker), new Resources(null, null));
        Map<String, String> images = Map.of("api", "img/api:1", "worker", "img/worker:1");

        List<String> args = builder.buildContainerArgs("my-poc", manifest, images);

        assertThat(blockFor("api", args)).contains("--startup-probe=httpGet.path=/healthz,httpGet.port=8080,timeoutSeconds=5,periodSeconds=10,failureThreshold=12");
        assertThat(blockFor("worker", args)).contains("--startup-probe=httpGet.path=/ready,httpGet.port=9000,timeoutSeconds=5,periodSeconds=10,failureThreshold=12");
    }

    /** health: stays optional — a manifest that declares none must still deploy. */
    @Test
    void emitsNoProbeForAContainerThatDeclaresNoHealthPath() {
        ManifestContainer api = new ManifestContainer("api", ContainerRole.INGRESS, "Dockerfile", ".", 8080, Map.of());
        ManifestContainer worker = new ManifestContainer("worker", ContainerRole.SIDECAR, "Dockerfile", ".", 9000, Map.of());
        PocManifest manifest = new PocManifest(List.of(api, worker), new Resources(null, null));

        List<String> args = builder.buildContainerArgs("my-poc", manifest,
                Map.of("api", "img/api:1", "worker", "img/worker:1"));

        assertThat(args.toString()).doesNotContain("--startup-probe");
    }

    /**
     * Without ordering the ingress can proxy to a sidecar that isn't listening yet — a 502 on every
     * cold start. Cloud Run only accepts a dependency on a container that has a startup probe, so
     * this lists exactly the probed sidecars.
     */
    @Test
    void makesTheIngressDependOnEverySidecarThatDeclaredAProbe() {
        ManifestContainer api = new ManifestContainer("api", ContainerRole.INGRESS, "Dockerfile", ".", 8080, Map.of(), "/healthz");
        ManifestContainer worker = new ManifestContainer("worker", ContainerRole.SIDECAR, "Dockerfile", ".", 9000, Map.of(), "/ready");
        ManifestContainer cache = new ManifestContainer("cache", ContainerRole.SIDECAR, "Dockerfile", ".", 9001, Map.of(), "/ready");
        PocManifest manifest = new PocManifest(List.of(api, worker, cache), new Resources(null, null));

        List<String> args = builder.buildContainerArgs("my-poc", manifest,
                Map.of("api", "img/api:1", "worker", "img/worker:1", "cache", "img/cache:1"));

        assertThat(blockFor("api", args)).contains("--depends-on=worker,cache");
    }

    /** Depending on a probe-less container is rejected by Cloud Run, so an unprobed sidecar is left out. */
    @Test
    void omitsDependsOnEntirelyWhenNoSidecarDeclaredAProbe() {
        ManifestContainer api = new ManifestContainer("api", ContainerRole.INGRESS, "Dockerfile", ".", 8080, Map.of(), "/healthz");
        ManifestContainer worker = new ManifestContainer("worker", ContainerRole.SIDECAR, "Dockerfile", ".", 9000, Map.of());
        PocManifest manifest = new PocManifest(List.of(api, worker), new Resources(null, null));

        List<String> args = builder.buildContainerArgs("my-poc", manifest,
                Map.of("api", "img/api:1", "worker", "img/worker:1"));

        assertThat(args.toString()).doesNotContain("--depends-on");
    }

    /** Scaling is service-level: it must never land inside a --container= block. */
    @Test
    void buildsScalingAsServiceLevelArgsSeparateFromAnyContainer() {
        ManifestContainer api = new ManifestContainer("api", ContainerRole.INGRESS, "Dockerfile", ".", 8080, Map.of());
        PocManifest manifest = new PocManifest(List.of(api), new Resources(null, null),
                new com.sails.ai.selfserviceapi.deploypipeline.manifest.Scaling(0, 3),
                com.sails.ai.selfserviceapi.deploypipeline.manifest.PlatformConfig.none());

        assertThat(builder.buildServiceArgs(manifest)).containsExactly("--min-instances=0", "--max-instances=3");
    }

    @Test
    void buildsNoServiceArgsWhenTheManifestDeclaresNoScaling() {
        ManifestContainer api = new ManifestContainer("api", ContainerRole.INGRESS, "Dockerfile", ".", 8080, Map.of());
        PocManifest manifest = new PocManifest(List.of(api), new Resources(null, null));

        assertThat(builder.buildServiceArgs(manifest)).isEmpty();
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
