package com.sails.ai.selfserviceapi.deploypipeline.run;

import com.sails.ai.selfserviceapi.deploypipeline.config.PocRuntimeProperties;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ContainerRole;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestContainer;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.PocManifest;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.Resources;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.Scaling;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Builds the arguments {@code gcloud run deploy} needs to turn a manifest into one Cloud Run
 * service. Shared by both executors ({@code BuildService}'s Cloud Build step and
 * {@code LocalPipelineExecutor}'s subprocess argv) — this flag shape is genuine cross-executor
 * platform knowledge that must not drift between the two, unlike the build steps themselves, which
 * are deliberately not shared.
 *
 * <p>Split in two on purpose. gcloud parses every flag after the first {@code --container=} as
 * scoped to that container and rejects anything it doesn't recognise as container-level with a
 * usage error (exit code 2) — a regression this repo has already shipped once. Callers therefore
 * emit their own credentials/region flags, then {@link #buildServiceArgs}, then
 * {@link #buildContainerArgs}, so the ordering constraint is expressed by the shape of this API
 * rather than by a comment each caller has to notice.
 */
@Component
public class CloudRunDeployCommandBuilder {

    private static final Logger log = LoggerFactory.getLogger(CloudRunDeployCommandBuilder.class);

    /**
     * gcloud's documented way to clear a container's port ("To unset this field, pass the special
     * value 'default'"). Emitted for every sidecar rather than simply saying nothing about its
     * port, because a deploy is a merge into the existing service, not a replacement: a service
     * first deployed by an earlier version of this builder — which handed the sidecar's own
     * declared port to gcloud as {@code --port} — keeps that port on the sidecar forever otherwise,
     * and Cloud Run rejects the whole revision with "should contain exactly one container with an
     * exposed port" once the ingress correctly gets one too. Saying it explicitly every time makes
     * the deploy self-healing instead of permanently stuck. It also retargets any TCP startup probe
     * gcloud had pointed at that port.
     */
    private static final String UNSET_PORT = "default";

    private final PocRuntimeProperties pocRuntime;

    public CloudRunDeployCommandBuilder(PocRuntimeProperties pocRuntime) {
        this.pocRuntime = pocRuntime;
    }

    /**
     * Flags belonging to the service as a whole rather than to any one container, so they must all
     * precede the first {@code --container=}. Scaling is the manifest's only say in this group;
     * everything else a caller emits here is its own (region, credentials, access).
     */
    public List<String> buildServiceArgs(PocManifest manifest) {
        List<String> args = new ArrayList<>();
        Scaling scaling = manifest.scaling();
        if (scaling == null) {
            return args;
        }
        if (scaling.min() != null) {
            args.add("--min-instances=" + scaling.min());
        }
        if (scaling.max() != null) {
            args.add("--max-instances=" + scaling.max());
        }
        return args;
    }

    /**
     * A single container — every pre-manifest POC, and the overwhelming majority of POCs even
     * after manifest support exists — deploys with the plain, single-image form
     * ({@code --image=<uri>}) rather than the multi-container {@code --container=<name>} syntax.
     * These are NOT equivalent in practice: {@code --container=} is a newer flag, and using it
     * unconditionally for every deploy (including plain single-container ones) broke every
     * existing POC's deploy with a gcloud usage error (exit code 2) the moment it shipped. Taking
     * on {@code --container=}'s syntax risk only when a manifest actually declares more than one
     * container keeps every deploy that worked before this feature working exactly as it did.
     */
    public List<String> buildContainerArgs(String pocSlug, PocManifest manifest, Map<String, String> imagesByContainer) {
        warnIfPlatformApiUrlIsUnreachableFromCloudRun(pocSlug);
        warnIfPortalOriginIsUnset(pocSlug);

        List<ManifestContainer> containers = manifest.containers();
        if (containers.size() == 1) {
            return buildSingleContainerArgs(pocSlug, containers.get(0), manifest.resources(), imagesByContainer);
        }
        return buildMultiContainerArgs(pocSlug, containers, manifest.resources(), imagesByContainer);
    }

    private List<String> buildSingleContainerArgs(String pocSlug, ManifestContainer container, Resources resources, Map<String, String> imagesByContainer) {
        List<String> args = new ArrayList<>();
        args.add("--image=" + requireImage(container, imagesByContainer));
        // No --port=: a single-container service is the one case Cloud Run does default a port for,
        // so it binds its own $PORT exactly as it did before this feature existed.
        addResourceArgs(resources, args);
        addStartupProbeArg(container, ingressPort(container), args);
        args.add(envArg(platformEnv(pocSlug, container, List.of(container))));
        return args;
    }

    /**
     * A single container's flags are "in scope" from its own --container= until the next one.
     *
     * <p>{@code --port=} is emitted for the ingress container only. Cloud Run's rule for a
     * multi-container service is "only one container can have the port exposed", and the container
     * holding that port <em>is</em> how Cloud Run identifies the ingress — there is no separate
     * field for it. A sidecar's {@code port} in the manifest exists so the platform can inject
     * {@code SVC_<NAME>_URL} and {@code PORT} for it, never to be handed to Cloud Run as a second
     * exposed port.
     */
    private List<String> buildMultiContainerArgs(String pocSlug, List<ManifestContainer> containers, Resources resources, Map<String, String> imagesByContainer) {
        List<String> args = new ArrayList<>();
        for (ManifestContainer container : containers) {
            args.add("--container=" + container.name());
            args.add("--image=" + requireImage(container, imagesByContainer));

            if (container.role() == ContainerRole.INGRESS) {
                int ingressPort = ingressPort(container);
                args.add("--port=" + ingressPort);
                addResourceArgs(resources, args);
                addStartupProbeArg(container, ingressPort, args);
                addDependsOnArg(containers, args);
            } else {
                args.add("--port=" + UNSET_PORT);
                addStartupProbeArg(container, container.port(), args);
            }

            args.add(envArg(platformEnv(pocSlug, container, containers)));
        }
        return args;
    }

    /**
     * A container's {@code health:} path becomes a Cloud Run startup probe against the port that
     * container actually listens on — the platform's ingress port for the ingress, its own declared
     * port for a sidecar. Skipped entirely when the manifest declared no path, so {@code health:}
     * stays optional; a sidecar the ingress depends on must have one (see {@link #addDependsOnArg}).
     */
    private void addStartupProbeArg(ManifestContainer container, Integer port, List<String> args) {
        if (container.health() == null || container.health().isBlank() || port == null) {
            return;
        }
        args.add("--startup-probe=httpGet.path=" + container.health() + ",httpGet.port=" + port);
    }

    /**
     * Without ordering, every container starts in parallel and the ingress can proxy to a sidecar
     * that isn't listening yet — a 502 on every cold start. Cloud Run accepts a dependency only on
     * a container that has a startup probe, so this lists exactly those sidecars that declared a
     * {@code health:} path, and is omitted entirely when none did. Tying it to {@code health:}
     * rather than emitting it for every sidecar keeps a probe-less manifest deployable instead of
     * turning an optional key into a required one.
     */
    private void addDependsOnArg(List<ManifestContainer> containers, List<String> args) {
        String probedSidecars = containers.stream()
                .filter(container -> container.role() == ContainerRole.SIDECAR)
                .filter(container -> container.health() != null && !container.health().isBlank() && container.port() != null)
                .map(ManifestContainer::name)
                .collect(Collectors.joining(","));
        if (!probedSidecars.isEmpty()) {
            args.add("--depends-on=" + probedSidecars);
        }
    }

    /**
     * Every container gets PLATFORM_API_URL (to verify a POC-scoped JWT against this API's JWKS),
     * POC_SLUG and PORTAL_ORIGIN, plus one SVC_&lt;NAME&gt;_URL per sidecar in the manifest other
     * than itself — a sidecar shares its ingress's network namespace, so it's reachable at plain
     * localhost:&lt;port&gt;, exactly the address
     * {@link com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestValidator} already
     * requires every sidecar to declare a port for.
     *
     * <p>POC_SLUG is what lets a POC's backend build its expected audience ({@code poc:<slug>})
     * from its own configuration rather than from the token it is checking — reading the expected
     * audience out of the object being validated is a check that validates nothing. PORTAL_ORIGIN
     * is both the postMessage targetOrigin and the POC's own frame-ancestors value, supplied here
     * precisely so a POC never derives it from {@code document.referrer} or
     * {@code location.ancestorOrigins}, both of which an embedder controls.
     *
     * <p>A sidecar additionally gets PORT, set to that same declared port. Cloud Run injects PORT
     * into the ingress container only, and {@link #buildMultiContainerArgs} deliberately clears
     * every sidecar's port ({@link #UNSET_PORT}) so exactly one container exposes one — which
     * leaves a sidecar with no way at all to learn the port the platform is simultaneously
     * advertising to everyone else as SVC_&lt;NAME&gt;_URL. It cannot supply the value itself either:
     * PORT is in {@code manifest.reserved-env-names}, so a manifest setting it is rejected. Without
     * this the sidecar binds whatever its image happens to default to, and every call through
     * SVC_&lt;NAME&gt;_URL fails with nothing pointing back at the manifest. This is the one place the
     * two values are written, so they cannot drift.
     */
    private Map<String, String> platformEnv(String pocSlug, ManifestContainer container, List<ManifestContainer> allContainers) {
        Map<String, String> env = new LinkedHashMap<>(container.env());
        env.put("PLATFORM_API_URL", pocRuntime.platformApiUrl());
        env.put("POC_SLUG", pocSlug);
        if (pocRuntime.hasPortalOrigin()) {
            env.put("PORTAL_ORIGIN", pocRuntime.portalOrigin());
        }
        if (container.role() == ContainerRole.SIDECAR && container.port() != null) {
            env.put("PORT", String.valueOf(container.port()));
        }
        for (ManifestContainer other : allContainers) {
            if (other == container || other.role() != ContainerRole.SIDECAR || other.port() == null) {
                continue;
            }
            String varName = "SVC_" + other.name().toUpperCase(Locale.ROOT).replace('-', '_') + "_URL";
            env.put(varName, "http://localhost:" + other.port());
        }
        return env;
    }

    /**
     * The ingress port is platform-owned ({@code poc-runtime.ingress-port}) — Cloud Run gives a
     * multi-container service no default for it, and only one container may expose one, so it is
     * not something a manifest decides per-container. A manifest that names its own ingress port
     * still wins: {@code poc-platform-sdk}'s published schema allows one and real POC repos are
     * already written against it, so rejecting it would break them for no gain.
     *
     * <p>This is also what keeps a rollback working. A redeploy re-parses the manifest text stored
     * with the version being rolled back to ({@code ManifestService.resolveStored}), which never
     * goes through validation and, for anything built before the ingress port was expressible, has
     * none at all — the platform default covers it instead of sending gcloud "--port=null".
     */
    private int ingressPort(ManifestContainer ingress) {
        return ingress.port() == null ? pocRuntime.ingressPort() : ingress.port();
    }

    /**
     * A localhost PLATFORM_API_URL resolves, inside the deployed container, to that container
     * itself — so the POC's own JWKS fetch 404s and every launch token it is handed fails
     * verification with a 401 that looks like a token problem rather than a config one. The deploy
     * still proceeds: this is only wrong for a POC that verifies tokens, and refusing here would
     * block deploying one that doesn't.
     */
    private void warnIfPlatformApiUrlIsUnreachableFromCloudRun(String pocSlug) {
        if (pocRuntime.platformApiUrlIsUnreachableFromCloudRun()) {
            log.warn("Deploying '{}' with PLATFORM_API_URL={} — a deployed container cannot reach that address. "
                    + "Set poc-runtime.platform-api-url (POC_RUNTIME_PLATFORM_API_URL) to this API's public URL, "
                    + "or the POC's JWKS lookup will fail and every launch token will be rejected.",
                    pocSlug, pocRuntime.platformApiUrl());
        }
    }

    /**
     * A POC given no PORTAL_ORIGIN has to fall back to accepting any origin for the portal
     * handshake and cannot set frame-ancestors at all. The deploy succeeds and the POC still loads,
     * so without this the gap stays invisible until someone audits the embedding.
     */
    private void warnIfPortalOriginIsUnset(String pocSlug) {
        if (!pocRuntime.hasPortalOrigin()) {
            log.warn("Deploying '{}' with no PORTAL_ORIGIN — set poc-runtime.portal-origin "
                    + "(POC_RUNTIME_PORTAL_ORIGIN), or the POC cannot restrict who may frame it.", pocSlug);
        }
    }

    private String requireImage(ManifestContainer container, Map<String, String> imagesByContainer) {
        String image = imagesByContainer.get(container.name());
        if (image == null || image.isBlank()) {
            throw new IllegalStateException("No built image for container '" + container.name() + "'");
        }
        return image;
    }

    /** Ingress only — Cloud Run bills the sum of every container's own limits; sidecars use its default. */
    private void addResourceArgs(Resources resources, List<String> args) {
        if (resources == null) {
            return;
        }
        if (resources.cpu() != null && !resources.cpu().isBlank()) {
            args.add("--cpu=" + resources.cpu());
        }
        if (resources.memory() != null && !resources.memory().isBlank()) {
            args.add("--memory=" + resources.memory());
        }
    }

    /**
     * Uses gcloud's alternate-delimiter syntax ({@code ^;^KEY=VALUE;KEY=VALUE}) rather than the
     * default comma-separated form, since a manifest author's env value is free text that may
     * itself contain a comma.
     */
    private String envArg(Map<String, String> env) {
        String joined = env.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((a, b) -> a + ";" + b)
                .orElse("");
        return "--set-env-vars=^;^" + joined;
    }
}
