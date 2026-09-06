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

    /** A single container's flags are "in scope" from its own --container= until the next one. */
    public List<String> buildContainerArgs(PocManifest manifest, Map<String, String> imagesByContainer) {
        List<String> args = new ArrayList<>();
        for (ManifestContainer container : manifest.containers()) {
            String image = imagesByContainer.get(container.name());
            if (image == null || image.isBlank()) {
                throw new IllegalStateException("No built image for container '" + container.name() + "'");
            }

            args.add("--container=" + container.name());
            args.add("--image=" + image);
            if (container.port() != null) {
                args.add("--port=" + container.port());
            }
            if (container.role() == ContainerRole.INGRESS) {
                addResourceArgs(manifest.resources(), args);
            }
            if (!container.env().isEmpty()) {
                args.add(envArg(container.env()));
            }
        }
        return args;
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
