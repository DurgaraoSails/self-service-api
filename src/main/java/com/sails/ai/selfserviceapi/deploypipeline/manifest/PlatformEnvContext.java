package com.sails.ai.selfserviceapi.deploypipeline.manifest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The values a {@code ${...}} placeholder in a manifest's {@code env:} can resolve to, for one
 * container. Built per container because {@code ${self.*}} means something different in each.
 *
 * <p>Deliberately holds plain values rather than the configuration objects they came from: it is
 * built both by the deploy path, from real runtime configuration, and by {@link ManifestValidator},
 * which has no slug and no deployment yet and only needs to know whether a reference <em>resolves</em>,
 * never to what. Keeping the lookup rules here rather than duplicating them in the validator is what
 * stops the two disagreeing about which references are legal.
 *
 * @param selfName        the container this context is for
 * @param selfPort        that container's effective port, or null when it has none — only reachable
 *                        through {@code ManifestService.resolveStored}, which never revalidates
 * @param portsByName     effective port per container name; a container with no port is absent
 * @param pocSlug         the POC's slug, or a stand-in during validation
 * @param platformApiUrl  the value injected as {@code PLATFORM_API_URL}
 * @param portalOrigin    the value injected as {@code PORTAL_ORIGIN}, or null when unconfigured
 */
public record PlatformEnvContext(
        String selfName,
        Integer selfPort,
        Map<String, Integer> portsByName,
        String pocSlug,
        String platformApiUrl,
        String portalOrigin
) {

    /**
     * @param ingressPort the <em>effective</em> ingress port, already resolved against
     *                    {@code poc-runtime.ingress-port} by the caller — passed in rather than
     *                    recomputed so that rule keeps living in exactly one place
     */
    public static PlatformEnvContext of(ManifestContainer self, List<ManifestContainer> containers, int ingressPort,
                                         String pocSlug, String platformApiUrl, String portalOrigin) {
        Map<String, Integer> ports = new LinkedHashMap<>();
        for (ManifestContainer container : containers) {
            // Written as an if rather than a ternary on purpose: mixing an int and an Integer in one
            // conditional unboxes both branches, so a sidecar with no port throws instead of being
            // skipped — and a portless sidecar is reachable here, through resolveStored.
            Integer port = container.port();
            if (container.role() == ContainerRole.INGRESS) {
                port = ingressPort;
            }
            if (port != null) {
                ports.put(container.name(), port);
            }
        }
        return new PlatformEnvContext(self.name(), ports.get(self.name()), Map.copyOf(ports),
                pocSlug, platformApiUrl, portalOrigin);
    }

    /**
     * The value for one placeholder expression ({@code self.port}, {@code services.backend.url}),
     * or empty when this context cannot supply it — which {@link EnvPlaceholders} treats as
     * "leave the text alone" and {@link ManifestValidator} reports as a violation.
     *
     * <p>A configured-but-absent platform value ({@code portal.origin} where none is set) resolves
     * to the empty string rather than to nothing, so the variable behaves the way it does today when
     * the platform simply doesn't inject it — not as a broken {@code ${portal.origin}} left in a
     * running container's environment.
     */
    public Optional<String> lookup(String expression) {
        String[] parts = expression.split("[.]", -1);
        return switch (parts[0]) {
            case "self" -> parts.length == 2 ? self(parts[1]) : Optional.empty();
            case "services" -> parts.length == 3 ? service(parts[1], parts[2]) : Optional.empty();
            case "poc" -> parts.length == 2 && parts[1].equals("slug") ? Optional.of(orEmpty(pocSlug)) : Optional.empty();
            case "platform" -> parts.length == 2 && parts[1].equals("apiUrl") ? Optional.of(orEmpty(platformApiUrl)) : Optional.empty();
            case "portal" -> parts.length == 2 && parts[1].equals("origin") ? Optional.of(orEmpty(portalOrigin)) : Optional.empty();
            default -> Optional.empty();
        };
    }

    private Optional<String> self(String property) {
        return switch (property) {
            case "port" -> selfPort == null ? Optional.empty() : Optional.of(String.valueOf(selfPort));
            case "name" -> Optional.of(selfName);
            default -> Optional.empty();
        };
    }

    /**
     * Any container may be addressed, the ingress included: every container in a Cloud Run service
     * shares one network namespace, so the address is well defined, and a sidecar calling back into
     * the ingress is a legitimate shape. This is a separate question from {@code SVC_<NAME>_URL},
     * which still never advertises the ingress — that rule is about what a browser must reach
     * through it.
     */
    private Optional<String> service(String name, String property) {
        Integer port = portsByName.get(name);
        if (port == null) {
            return Optional.empty();
        }
        return switch (property) {
            case "url" -> Optional.of("http://localhost:" + port);
            case "host" -> Optional.of("localhost");
            case "port" -> Optional.of(String.valueOf(port));
            default -> Optional.empty();
        };
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
