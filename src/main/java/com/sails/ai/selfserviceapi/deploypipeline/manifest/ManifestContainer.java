package com.sails.ai.selfserviceapi.deploypipeline.manifest;

import java.util.Map;

/**
 * One container declared in a POC's poc.yaml. {@code dockerfile}/{@code context} are always
 * resolved (defaulted by {@link ManifestParser}, never left null) so nothing downstream needs to
 * repeat the "Dockerfile"/"." default.
 *
 * <p>{@code dockerfile} is relative to {@code context}, matching Docker Compose's own
 * {@code build.dockerfile}/{@code build.context} convention — a container whose context is
 * {@code worker} and whose Dockerfile is at {@code worker/Dockerfile} writes {@code context:
 * worker} and just {@code dockerfile: Dockerfile} (the default), not {@code worker/Dockerfile}.
 * Use {@link #dockerfilePath()} rather than the raw {@code dockerfile} field wherever a path
 * relative to the repo root is actually needed (i.e. everywhere a build step runs).
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

    /** {@code dockerfile} resolved against {@code context}, so callers never re-derive this joining themselves. */
    public String dockerfilePath() {
        return ".".equals(context) ? dockerfile : context + "/" + dockerfile;
    }
}
