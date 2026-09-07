package com.sails.ai.selfserviceapi.deploypipeline.manifest;

import java.util.Map;

/**
 * One container declared in a POC's poc.yaml. {@code dockerfile}/{@code context} are always
 * resolved (defaulted by {@link ManifestParser}, never left null) so nothing downstream needs to
 * repeat the "Dockerfile"/"." default.
 *
 * <p>Both {@code dockerfile} and {@code context} are independent paths relative to the repo
 * root — NOT {@code dockerfile} relative to {@code context}. A container whose Dockerfile lives at
 * {@code apps/frontend/Dockerfile} writes exactly that as {@code dockerfile}, alongside whatever
 * {@code context} it needs (typically the same directory), matching the platform's established
 * {@code poc.yaml} contract (see {@code poc-platform-sdk}'s schema) that real POC repos are
 * already written against.
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
        Map<String, String> env,

        /**
         * An HTTP path (e.g. {@code /healthz}) this container answers on its declared port.
         * {@code null} means the manifest declared none — not yet wired to a Cloud Run startup/
         * liveness probe, so it has no effect on the deploy today; kept so it survives parsing
         * instead of being silently dropped, for when that wiring is added.
         */
        String health
) {

    /** Pre-{@link #health} call sites: defaults it to {@code null} (no probe declared). */
    public ManifestContainer(String name, ContainerRole role, String dockerfile, String context, Integer port, Map<String, String> env) {
        this(name, role, dockerfile, context, port, env, null);
    }
}
