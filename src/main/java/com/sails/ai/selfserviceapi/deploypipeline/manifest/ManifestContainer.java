package com.sails.ai.selfserviceapi.deploypipeline.manifest;

import java.util.Map;

/**
 * One container declared in a POC's poc.yaml. {@code dockerfile}/{@code context} are always
 * resolved (defaulted by {@link ManifestParser}, never left null) so nothing downstream needs to
 * repeat the "Dockerfile"/"." default.
 *
 * <p>Deliberately has no {@code repo} field — every container this phase builds comes from the
 * primary repo. Adding an optional repo reference later, for a future cross-repository phase, is
 * purely additive here: no existing manifest's meaning would change.
 */
public record ManifestContainer(
        String name,
        ContainerRole role,
        String dockerfile,
        String context,
        Integer port,
        Map<String, String> env
) {
}
