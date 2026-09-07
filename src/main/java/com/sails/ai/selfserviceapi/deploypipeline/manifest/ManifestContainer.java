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
         * An HTTP path (e.g. {@code /healthz}) this container answers on its declared port,
         * deployed as a Cloud Run startup probe against that port. {@code null} means the manifest
         * declared none, which stays valid — but only a sidecar that declares one can be depended
         * on by the ingress, since Cloud Run rejects a dependency on a container with no startup
         * probe (see {@code CloudRunDeployCommandBuilder}).
         */
        String health,

        /**
         * A container built from a different repository than the POC's own. Parsed only so
         * {@link ManifestValidator} can reject it by name: every container this phase builds comes
         * from the primary repo, and a key that simply vanished would leave an author guessing why
         * their intent was ignored. Rejected, never silently dropped.
         */
        String repo
) {

    /** Pre-{@link #repo} call sites: defaults it to {@code null} (no cross-repo container declared). */
    public ManifestContainer(String name, ContainerRole role, String dockerfile, String context, Integer port,
                              Map<String, String> env, String health) {
        this(name, role, dockerfile, context, port, env, health, null);
    }

    /** Pre-{@link #health} call sites: defaults it to {@code null} (no probe declared). */
    public ManifestContainer(String name, ContainerRole role, String dockerfile, String context, Integer port, Map<String, String> env) {
        this(name, role, dockerfile, context, port, env, null, null);
    }
}
