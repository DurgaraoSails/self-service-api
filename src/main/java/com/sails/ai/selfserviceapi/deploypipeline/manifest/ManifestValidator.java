package com.sails.ai.selfserviceapi.deploypipeline.manifest;

import com.sails.ai.selfserviceapi.deploypipeline.config.PocRuntimeProperties;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final PocRuntimeProperties pocRuntime;

    /**
     * {@code pocRuntime} is read for one thing only: the ingress port an ingress container gets when
     * it declares none. Without it the most likely port collision of all — a sidecar on 8080 beside
     * an ingress that simply took the platform default — is the one case this validator could not
     * see, and it would surface as two containers failing to bind rather than as a manifest error.
     */
    public ManifestValidator(ManifestProperties properties, PocRuntimeProperties pocRuntime) {
        this.properties = properties;
        this.pocRuntime = pocRuntime;
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
            validateEnvPlaceholders(container, containers, violations);
        }

        validatePortCollisions(containers, violations);
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

    /**
     * A placeholder that names a known root but resolves to nothing is a mistake, not a literal.
     * Left to deploy it would reach the container verbatim and fail there as a connection error
     * naming a host nobody wrote — about as far from its cause as a symptom gets. Caught here it is
     * one line naming the container, the variable and the reference.
     *
     * <p>Only known roots are checked. {@link EnvPlaceholders} leaves anything else alone, so an
     * existing manifest whose env value merely contains {@code ${...}} does not become invalid.
     */
    private void validateEnvPlaceholders(ManifestContainer container, List<ManifestContainer> containers,
                                          List<String> violations) {
        PlatformEnvContext context = validationContext(container, containers);
        container.env().forEach((key, value) ->
                EnvPlaceholders.unresolvableReferences(value, context).forEach(reference ->
                        violations.add("container '" + container.name() + "' sets env var '" + key + "' to a value"
                                + " referencing '${" + reference + "}', which names nothing this manifest declares"
                                + " — check the container name and property, or write '$${' to keep a literal '${'")));
    }

    /**
     * Values here are stand-ins: this asks only whether a reference <em>resolves</em>, never to what.
     * The slug and the platform URLs are not known at validation time and no rule depends on them.
     * The ingress port is the exception — it is a real value, because a reference to it must agree
     * with what the deploy will actually inject.
     */
    private PlatformEnvContext validationContext(ManifestContainer container, List<ManifestContainer> containers) {
        return PlatformEnvContext.of(container, containers, ingressPort(containers), "", "", "");
    }

    /**
     * Every container in a Cloud Run service shares one network namespace, so two containers on one
     * port cannot both bind. Before that even matters, two sidecars on 8081 would both be advertised
     * as {@code SVC_<NAME>_URL=http://localhost:8081}, leaving one of them permanently unreachable
     * at an address that looks perfectly correct. Neither failure points back at the manifest.
     *
     * <p>An ingress declaring no port is compared at the platform's own ingress port rather than
     * skipped. Taking that default is what most manifests do, and a sidecar on 8080 beside one is
     * the likeliest collision there is — skipping it would leave exactly the common case unchecked.
     */
    private void validatePortCollisions(List<ManifestContainer> containers, List<String> violations) {
        int ingressPort = ingressPort(containers);
        Map<Integer, List<String>> namesByPort = new LinkedHashMap<>();
        for (ManifestContainer container : containers) {
            // An if, not a ternary: mixing int and Integer would unbox both branches and throw on a
            // sidecar that declared no port — which is a violation this pass still has to report.
            Integer port = container.port();
            if (container.role() == ContainerRole.INGRESS) {
                port = ingressPort;
            }
            if (port != null) {
                namesByPort.computeIfAbsent(port, key -> new ArrayList<>()).add("'" + container.name() + "'");
            }
        }
        namesByPort.forEach((port, names) -> {
            if (names.size() > 1) {
                violations.add("containers " + String.join(" and ", names) + " would both use port " + port
                        + " — containers in one service share a network namespace, so each port belongs to"
                        + " exactly one of them");
            }
        });
    }

    /** The port the ingress container will actually bind: its own if it named one, the platform's otherwise. */
    private int ingressPort(List<ManifestContainer> containers) {
        for (ManifestContainer container : containers) {
            if (container.role() == ContainerRole.INGRESS && container.port() != null) {
                return container.port();
            }
        }
        return pocRuntime.ingressPort();
    }
}
