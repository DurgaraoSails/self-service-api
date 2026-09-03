package com.sails.ai.selfserviceapi.deploypipeline.run;

import com.sails.ai.selfserviceapi.deploypipeline.config.GcpProperties;
import com.sails.ai.selfserviceapi.deploypipeline.config.PipelineProperties;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ContainerRole;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestContainer;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.PocManifest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Builds the argument list for {@code gcloud run deploy}, shared by {@link
 * com.sails.ai.selfserviceapi.deploypipeline.LocalPipelineExecutor} and {@link
 * com.sails.ai.selfserviceapi.deploypipeline.build.BuildService} so the --container/--depends-on/
 * --startup-probe flag logic exists in exactly one place, not duplicated and liable to drift.
 *
 * <p>{@code --container}, {@code --depends-on} and {@code --startup-probe} were verified against
 * an actually installed {@code gcloud run deploy --help} while designing this, not recalled from
 * memory. Flags are returned as a plain arg list — passed as argv to a subprocess or as a Cloud
 * Build step's {@code args}, never through a shell — so nothing here needs to worry about shell
 * quoting.
 */
@Component
public class CloudRunDeployCommandBuilder {

    /**
     * Cloud Run's own default when a container declares no explicit port. Fixed rather than
     * configurable — the ingress container's actual bind port is what $PORT tells it, and every
     * POC template already reads that variable rather than assuming a literal number.
     */
    private static final int INGRESS_PORT = 8080;

    public List<String> buildDeployArgs(String slug, String versionLabel, PocManifest manifest,
                                         Map<String, String> imagesByContainer,
                                         GcpProperties gcp, PipelineProperties properties) {
        List<ManifestContainer> sidecars = manifest.sidecars();
        String sidecarNames = sidecars.stream().map(ManifestContainer::name).collect(Collectors.joining(","));

        List<String> args = new ArrayList<>(List.of(
                "run", "deploy", slug,
                "--region=" + gcp.region(),
                "--service-account=" + gcp.serviceAccountEmail("poc-runtime"),
                properties.allowUnauthenticated() ? "--allow-unauthenticated" : "--no-allow-unauthenticated",
                "--min-instances=" + manifest.scaling().min(),
                "--max-instances=" + manifest.scaling().max(),
                "--quiet"));

        ManifestContainer ingress = manifest.ingress();
        args.add("--container=" + ingress.name());
        args.add("--image=" + requireImage(ingress, imagesByContainer));
        args.add("--port=" + INGRESS_PORT);
        args.add("--cpu=" + manifest.resources().cpu());
        args.add("--memory=" + manifest.resources().memory());
        if (!sidecars.isEmpty()) {
            args.add("--depends-on=" + sidecarNames);
        }
        args.add("--set-env-vars=" + envVars(ingress, versionLabel, sidecars, slug));

        for (ManifestContainer sidecar : sidecars) {
            args.add("--container=" + sidecar.name());
            args.add("--image=" + requireImage(sidecar, imagesByContainer));
            args.add("--startup-probe=httpGet.port=%d,httpGet.path=%s".formatted(sidecar.port(), sidecar.health()));
            args.add("--set-env-vars=" + envVars(sidecar, versionLabel, sidecars, slug));
        }

        return args;
    }

    private String requireImage(ManifestContainer container, Map<String, String> imagesByContainer) {
        String image = imagesByContainer.get(container.name());
        if (image == null) {
            throw new IllegalStateException(
                    "no built image for container '" + container.name() + "' — buildAndPushImages must return one entry per manifest container");
        }
        return image;
    }

    /**
     * PLATFORM_API_URL/PORTAL_ORIGIN/DATABASE_URL are intentionally absent here — they're Phase
     * 3/4 concepts with no real value to inject yet, and injecting a fabricated placeholder would
     * look like platform integration that doesn't exist. BASE_PATH is different: empty is the
     * genuinely correct value today (there is no path prefix yet), not a stand-in for one.
     */
    private String envVars(ManifestContainer container, String versionLabel, List<ManifestContainer> sidecars, String slug) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("POC_SLUG", slug);
        env.put("POC_VERSION", versionLabel);
        env.put("BASE_PATH", "");
        if (container.role() == ContainerRole.SIDECAR) {
            env.put("PORT", String.valueOf(container.port()));
        }
        for (ManifestContainer sidecar : sidecars) {
            env.put("SVC_" + sidecar.name().toUpperCase().replace('-', '_') + "_URL",
                    "http://localhost:" + sidecar.port());
        }
        env.putAll(container.env()); // manifest-declared last; ManifestValidator already forbids reserved-name collisions

        return env.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(","));
    }
}
