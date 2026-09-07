package com.sails.ai.selfserviceapi.deploypipeline.manifest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Every rule a poc.yaml must satisfy before anything is cloned or built. Reports every violation
 * found in one pass — not fail-fast — so a manifest author gets a complete list to fix, not one
 * error per retry.
 */
@Component
public class ManifestValidator {

    /** Same convention as {@code Poc.slug} — a container name becomes part of an image path and a Cloud Run flag value. */
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    /** Matches the poc_version_containers.name column width — not made configurable, see the plan's genericization notes. */
    private static final int MAX_NAME_LENGTH = 40;

    private final ManifestProperties properties;

    public ManifestValidator(ManifestProperties properties) {
        this.properties = properties;
    }

    public List<String> validate(PocManifest manifest) {
        List<String> violations = new ArrayList<>();
        List<ManifestContainer> containers = manifest.containers();

        if (containers.size() > properties.maxContainers()) {
            violations.add("a manifest may declare at most " + properties.maxContainers()
                    + " containers, found " + containers.size());
        }

        long ingressCount = containers.stream().filter(c -> c.role() == ContainerRole.INGRESS).count();
        if (ingressCount == 0) {
            violations.add("exactly one container must have role 'ingress' — none was found");
        } else if (ingressCount > 1) {
            violations.add("exactly one container must have role 'ingress' — found " + ingressCount);
        }

        // Cloud Run gives a single-container service a default port (8080) automatically, but a
        // service with sidecars gets no default for its ingress container at all — the ingress
        // must declare one explicitly, or Cloud Run has nothing to route external traffic to. See
        // https://cloud.google.com/run/docs/configuring/services/containers.
        boolean hasSidecars = containers.stream().anyMatch(c -> c.role() == ContainerRole.SIDECAR);

        Set<String> seenNames = new HashSet<>();
        for (ManifestContainer container : containers) {
            validateName(container, seenNames, violations);
            validatePort(container, hasSidecars, violations);
            validateEnv(container, violations);
        }

        return violations;
    }

    private void validateName(ManifestContainer container, Set<String> seenNames, List<String> violations) {
        String name = container.name();
        if (name == null || !NAME_PATTERN.matcher(name).matches() || name.length() > MAX_NAME_LENGTH) {
            violations.add("container name '" + name + "' must be lowercase alphanumeric with hyphens, at most "
                    + MAX_NAME_LENGTH + " characters");
        } else if (!seenNames.add(name)) {
            violations.add("container name '" + name + "' is declared more than once");
        }
    }

    /**
     * A lone ingress container (no sidecars) deploys through the plain, single-image
     * {@code --image=} form, which Cloud Run defaults to port 8080 for — any {@code port} it
     * declares would be meaningless, so it's neither required nor forbidden. Once a sidecar
     * exists, the deploy switches to the multi-container {@code --container=} form, where Cloud
     * Run's own rule is "only one container can have the port exposed" and gives the ingress
     * container no default — so the ingress must declare one there, unconditionally.
     */
    private void validatePort(ManifestContainer container, boolean hasSidecars, List<String> violations) {
        if (container.role() == ContainerRole.INGRESS) {
            if (hasSidecars && container.port() == null) {
                violations.add("ingress container '" + container.name()
                        + "' must declare a port when the manifest has sidecars — Cloud Run has no"
                        + " default port for a multi-container service's ingress container");
            }
            return;
        }
        if (container.port() == null) {
            violations.add("sidecar container '" + container.name()
                    + "' must declare a port — it's only reachable at an address the ingress names");
        }
    }

    private void validateEnv(ManifestContainer container, List<String> violations) {
        container.env().keySet().forEach(key -> {
            if (properties.reservedEnvNames().contains(key)) {
                violations.add("container '" + container.name() + "' sets reserved env var '" + key + "'");
            } else if (properties.reservedEnvPrefixes().stream().anyMatch(key::startsWith)) {
                violations.add("container '" + container.name() + "' sets env var '" + key
                        + "', which uses a reserved prefix");
            }
        });
    }
}
