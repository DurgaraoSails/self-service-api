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

        Set<String> seenNames = new HashSet<>();
        for (ManifestContainer container : containers) {
            validateName(container, seenNames, violations);
            validatePort(container, violations);
            validateRepo(container, violations);
            validateEnv(container, violations);
        }

        validateScaling(manifest.scaling(), violations);

        return violations;
    }

    /** Both flow straight into --min-instances/--max-instances — validated here rather than as a gcloud usage error partway through a deploy. */
    private void validateScaling(Scaling scaling, List<String> violations) {
        Integer min = scaling.min();
        Integer max = scaling.max();
        if (min != null && min < 0) {
            violations.add("scaling.min must be zero or greater, got " + min);
        }
        if (max != null && max < 1) {
            violations.add("scaling.max must be at least 1, got " + max);
        }
        if (min != null && max != null && min > max) {
            violations.add("scaling.min (" + min + ") must not exceed scaling.max (" + max + ")");
        }
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
     * An ingress port is permitted but never required. Cloud Run gives a multi-container service's
     * ingress no default port, but the platform owns that value
     * ({@code poc-runtime.ingress-port}) rather than making every manifest restate it — so a
     * manifest declaring none is complete, and one declaring its own still wins at deploy time
     * ({@code CloudRunDeployCommandBuilder.ingressPort}). Requiring it instead would break every
     * multi-container repo already written against {@code poc-platform-sdk}'s published schema,
     * for a value the platform can always supply.
     *
     * <p>A sidecar's port is required and means something different: it is the only address the
     * ingress can reach it at, and what the platform injects as that sidecar's own {@code PORT}.
     */
    private void validatePort(ManifestContainer container, List<String> violations) {
        if (container.role() == ContainerRole.INGRESS) {
            return;
        }
        if (container.port() == null) {
            violations.add("sidecar container '" + container.name()
                    + "' must declare a port — it's only reachable at an address the ingress names");
        }
    }

    /**
     * Rejected rather than ignored. Every container this phase builds comes from the POC's primary
     * repo, so a {@code repo:} key changes nothing about what gets deployed — and a key that
     * silently does nothing is exactly how an author ends up believing their second repository was
     * built. Saying so costs one message and removes the guesswork.
     */
    private void validateRepo(ManifestContainer container, List<String> violations) {
        if (container.repo() != null && !container.repo().isBlank()) {
            violations.add("container '" + container.name() + "' declares repo '" + container.repo()
                    + "' — cross-repository containers aren't supported yet; every container builds"
                    + " from this POC's own repository");
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
