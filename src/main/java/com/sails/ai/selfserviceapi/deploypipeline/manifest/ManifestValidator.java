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

    private void validatePort(ManifestContainer container, List<String> violations) {
        if (container.role() == ContainerRole.INGRESS && container.port() != null) {
            violations.add("ingress container '" + container.name()
                    + "' must not declare a port — it receives Cloud Run's own $PORT");
        }
        if (container.role() == ContainerRole.SIDECAR && container.port() == null) {
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
