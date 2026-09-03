package com.sails.ai.selfserviceapi.deploypipeline.manifest;

import java.util.Map;

/**
 * One container declared in poc.yaml. {@code repo} exists only so the validator can reject it
 * with a clear message ("cross-repository containers aren't supported yet") — this phase always
 * builds from the primary repo, but keeping the field here means supporting it later is additive
 * to {@link ManifestService}, not a rework of this record.
 */
public record ManifestContainer(
        String name,
        ContainerRole role,
        String dockerfile,
        String context,
        Integer port,
        String health,
        String repo,
        Map<String, String> env
) {
}
