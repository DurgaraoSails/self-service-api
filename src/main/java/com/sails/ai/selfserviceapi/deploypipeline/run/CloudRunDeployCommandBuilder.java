package com.sails.ai.selfserviceapi.deploypipeline.run;

import com.sails.ai.selfserviceapi.deploypipeline.manifest.ContainerRole;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestContainer;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.PocManifest;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.Resources;
import java.util.ArrayList;
import java.util.List;
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
    public List<String> buildContainerArgs(PocManifest manifest, Map<String, String> imagesByContainer) {
        List<ManifestContainer> containers = manifest.containers();
        if (containers.size() == 1) {
            return buildSingleContainerArgs(containers.get(0), manifest.resources(), imagesByContainer);
        }
        return buildMultiContainerArgs(containers, manifest.resources(), imagesByContainer);
    }

    private List<String> buildSingleContainerArgs(ManifestContainer container, Resources resources, Map<String, String> imagesByContainer) {
        List<String> args = new ArrayList<>();
        args.add("--image=" + requireImage(container, imagesByContainer));
        // No --port=: the validator already requires a manifest's sole (necessarily ingress)
        // container to declare none — it binds Cloud Run's own $PORT, same as before this feature.
        addResourceArgs(resources, args);
        if (!container.env().isEmpty()) {
            args.add(envArg(container.env()));
        }
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
    private List<String> buildMultiContainerArgs(List<ManifestContainer> containers, Resources resources, Map<String, String> imagesByContainer) {
        List<String> args = new ArrayList<>();
        for (ManifestContainer container : containers) {
            args.add("--container=" + container.name());
            args.add("--image=" + requireImage(container, imagesByContainer));
            if (container.role() == ContainerRole.INGRESS) {
                args.add("--port=" + container.port());
                addResourceArgs(resources, args);
            }
            if (!container.env().isEmpty()) {
                args.add(envArg(container.env()));
            }
        }
        return args;
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
