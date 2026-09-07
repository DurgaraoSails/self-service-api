package com.sails.ai.selfserviceapi.deploypipeline.manifest;

import java.util.List;

/**
 * A POC's fully resolved deploy shape — one or more containers (exactly one INGRESS, the rest
 * SIDECAR) plus one resources block for the whole service. Every repo has one of these at build
 * time: either parsed from its own poc.yaml, or {@link ManifestService}'s synthesized single
 * "app" ingress container default for a repo with none.
 */
public record PocManifest(List<ManifestContainer> containers, Resources resources, Scaling scaling, PlatformConfig platform) {

    /** Pre-{@link #scaling}/{@link #platform} call sites: defaults both to "none declared". */
    public PocManifest(List<ManifestContainer> containers, Resources resources) {
        this(containers, resources, Scaling.none(), PlatformConfig.none());
    }

    public ManifestContainer ingress() {
        return containers.stream()
                .filter(container -> container.role() == ContainerRole.INGRESS)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Manifest has no ingress container — should have been rejected by ManifestValidator"));
    }
}
