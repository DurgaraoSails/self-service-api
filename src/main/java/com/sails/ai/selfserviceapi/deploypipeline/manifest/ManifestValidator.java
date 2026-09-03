package com.sails.ai.selfserviceapi.deploypipeline.manifest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Validates a parsed manifest before anything is cloned or built — a bad manifest should fail
 * fast with an actionable message, not surface as a confusing build-step error three minutes in.
 */
@Component
public class ManifestValidator {

    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    /** Cloud Run's own per-service container limit. */
    private static final int MAX_CONTAINERS = 8;

    private static final Set<String> RESERVED_ENV_NAMES = Set.of(
            "PORT", "DATABASE_URL", "PLATFORM_API_URL", "POC_SLUG",
            "POC_VERSION", "BASE_PATH", "PORTAL_ORIGIN");
    private static final Set<String> RESERVED_ENV_PREFIXES = Set.of("SAILS_", "SVC_");

    public void validate(PocManifest manifest) {
        List<String> violations = new ArrayList<>();

        if (!PocManifest.API_VERSION.equals(manifest.apiVersion())) {
            violations.add("apiVersion must be \"%s\", found \"%s\""
                    .formatted(PocManifest.API_VERSION, manifest.apiVersion()));
        }

        List<ManifestContainer> containers = manifest.containers();
        if (containers.isEmpty()) {
            violations.add("containers must declare at least one entry");
        }
        if (containers.size() > MAX_CONTAINERS) {
            violations.add("containers has %d entries; Cloud Run allows at most %d per service"
                    .formatted(containers.size(), MAX_CONTAINERS));
        }

        long ingressCount = containers.stream().filter(c -> c.role() == ContainerRole.INGRESS).count();
        if (ingressCount != 1) {
            violations.add("exactly one container must have role: ingress, found " + ingressCount);
        }

        Set<String> seenNames = new HashSet<>();
        Set<Integer> seenSidecarPorts = new HashSet<>();
        for (ManifestContainer container : containers) {
            validateContainer(container, seenNames, seenSidecarPorts, violations);
        }

        if (!violations.isEmpty()) {
            throw new InvalidPocManifestException(violations);
        }
    }

    private void validateContainer(ManifestContainer container, Set<String> seenNames,
                                    Set<Integer> seenSidecarPorts, List<String> violations) {
        String name = container.name();

        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            violations.add("container name \"%s\" must match %s".formatted(name, NAME_PATTERN.pattern()));
        } else if (!seenNames.add(name)) {
            violations.add("container name \"%s\" is declared more than once".formatted(name));
        }

        if (container.role() == null) {
            violations.add("container \"%s\" has an invalid or missing role — must be \"ingress\" or \"sidecar\""
                    .formatted(name));
        } else if (container.role() == ContainerRole.INGRESS && container.port() != null) {
            violations.add("container \"%s\" is the ingress container and must not declare a port — it binds $PORT"
                    .formatted(name));
        } else if (container.role() == ContainerRole.SIDECAR) {
            if (container.port() == null) {
                violations.add(("sidecar \"%s\" must declare a port — it's only reachable at an address "
                        + "the ingress container can name").formatted(name));
            } else if (!seenSidecarPorts.add(container.port())) {
                violations.add("sidecar port %d is declared by more than one container".formatted(container.port()));
            }
        }

        if (container.repo() != null && !container.repo().isBlank()) {
            violations.add("container \"%s\" sets \"repo\" — cross-repository containers aren't supported yet"
                    .formatted(name));
        }

        for (String envKey : container.env().keySet()) {
            if (isReservedEnvName(envKey)) {
                violations.add("container \"%s\" sets reserved env var \"%s\"".formatted(name, envKey));
            }
        }
        for (String envValue : container.env().values()) {
            if (envValue != null && envValue.contains(",")) {
                violations.add(("container \"%s\" has an env value containing a comma — not yet supported, "
                        + "since env vars are passed to gcloud as a comma-separated list").formatted(name));
            }
        }
    }

    private boolean isReservedEnvName(String key) {
        if (RESERVED_ENV_NAMES.contains(key)) {
            return true;
        }
        return RESERVED_ENV_PREFIXES.stream().anyMatch(key::startsWith);
    }
}
