package com.sails.ai.selfserviceapi.deploypipeline.manifest;

import java.util.List;

/**
 * A validated poc.yaml — or the manifest {@link ManifestService} synthesizes when a repo has
 * none, which reproduces today's single-container behavior exactly (one container named "app",
 * the root Dockerfile).
 */
public record PocManifest(
        String apiVersion,
        String name,
        String description,
        String team,
        List<ManifestContainer> containers,
        Resources resources,
        Scaling scaling,
        Platform platform
) {

    public static final String API_VERSION = "sails.poc/v1";

    /** Safe to call only on a manifest that has already passed {@link ManifestValidator}. */
    public ManifestContainer ingress() {
        return containers.stream()
                .filter(c -> c.role() == ContainerRole.INGRESS)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "manifest has no ingress container — ManifestValidator should have rejected this already"));
    }

    public List<ManifestContainer> sidecars() {
        return containers.stream().filter(c -> c.role() == ContainerRole.SIDECAR).toList();
    }

    public static PocManifest defaultSingleContainer() {
        ManifestContainer app = new ManifestContainer(
                "app", ContainerRole.INGRESS, "Dockerfile", ".", null, "/healthz", null, java.util.Map.of());
        return new PocManifest(API_VERSION, null, null, null,
                List.of(app), Resources.defaults(), Scaling.defaults(), Platform.none());
    }
}
