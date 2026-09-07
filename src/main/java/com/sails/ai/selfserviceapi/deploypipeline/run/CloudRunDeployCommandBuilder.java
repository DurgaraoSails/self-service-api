package com.sails.ai.selfserviceapi.deploypipeline.run;

import com.sails.ai.selfserviceapi.deploypipeline.config.PipelineProperties;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ContainerRole;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestContainer;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.PocManifest;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.Resources;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Builds the repeated {@code --container=} block of arguments {@code gcloud run deploy} needs to
 * deploy a manifest's containers as one Cloud Run service. Shared between both executors' deploy
 * step (today: {@code BuildService}'s Cloud Build step; later, Phase 2: the local executor's
 * subprocess argv) — this flag shape is genuine cross-executor platform knowledge that must not
 * drift between the two, unlike the build steps themselves, which are deliberately not shared.
 */
@Component
public class CloudRunDeployCommandBuilder {

    /** Cloud Run's own default container port, used only for the stored-manifest case in {@link #ingressPort}. */
    private static final int DEFAULT_INGRESS_PORT = 8080;

    private final PipelineProperties properties;

    public CloudRunDeployCommandBuilder(PipelineProperties properties) {
        this.properties = properties;
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
        List<ManifestContainer> containers = manifest.containers();
        if (containers.size() == 1) {
            return buildSingleContainerArgs(pocSlug, containers.get(0), manifest.resources(), imagesByContainer);
        }
        return buildMultiContainerArgs(pocSlug, containers, manifest.resources(), imagesByContainer);
    }

    private List<String> buildSingleContainerArgs(String pocSlug, ManifestContainer container, Resources resources, Map<String, String> imagesByContainer) {
        List<String> args = new ArrayList<>();
        args.add("--image=" + requireImage(container, imagesByContainer));
        // No --port=: the validator already requires a manifest's sole (necessarily ingress)
        // container to declare none — it binds Cloud Run's own $PORT, same as before this feature.
        addResourceArgs(resources, args);
        args.add(envArg(platformEnv(pocSlug, container, List.of(container))));
        return args;
    }

    /**
     * A single container's flags are "in scope" from its own --container= until the next one.
     *
     * <p>{@code --port=} is emitted for the ingress container only, and only from its own
     * declared port — never a sidecar's. Cloud Run's rule for a multi-container service is "only
     * one container can have the port exposed"; a sidecar's {@code port} in the manifest exists so
     * the platform can inject {@code SVC_<NAME>_URL} for other containers to reach it over
     * localhost, not to be handed to Cloud Run as this container's public port.
     */
    private List<String> buildMultiContainerArgs(String pocSlug, List<ManifestContainer> containers, Resources resources, Map<String, String> imagesByContainer) {
        List<String> args = new ArrayList<>();
        for (ManifestContainer container : containers) {
            args.add("--container=" + container.name());
            args.add("--image=" + requireImage(container, imagesByContainer));
            if (container.role() == ContainerRole.INGRESS) {
                args.add("--port=" + ingressPort(container));
                addResourceArgs(resources, args);
            }
            args.add(envArg(platformEnv(pocSlug, container, containers)));
        }
        return args;
    }

    /**
     * Every container gets PLATFORM_API_URL (to verify a POC-scoped JWT against this API's JWKS)
     * and POC_SLUG, plus one SVC_&lt;NAME&gt;_URL per sidecar in the manifest other than itself — a
     * sidecar shares its ingress's network namespace, so it's reachable at plain localhost:&lt;port&gt;,
     * exactly the address {@link com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestValidator}
     * already requires every sidecar to declare a port for.
     */
    private Map<String, String> platformEnv(String pocSlug, ManifestContainer container, List<ManifestContainer> allContainers) {
        Map<String, String> env = new LinkedHashMap<>(container.env());
        env.put("PLATFORM_API_URL", properties.platformApiUrl());
        env.put("POC_SLUG", pocSlug);
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
     * {@link com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestValidator} requires an
     * ingress port whenever a manifest has sidecars, so a freshly built version always declares
     * one. A redeploy or rollback does not go through that validation — it re-parses the manifest
     * text stored with the version being rolled back to ({@code ManifestService.resolveStored}),
     * which for any version built before that rule existed has no ingress port at all. Falling
     * back to Cloud Run's own default keeps those versions rollable instead of sending gcloud the
     * literal string "--port=null"; failing here instead would strand them permanently, since a
     * stored manifest is immutable history and there is nothing an admin could edit to fix it.
     */
    private int ingressPort(ManifestContainer ingress) {
        return ingress.port() == null ? DEFAULT_INGRESS_PORT : ingress.port();
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
