package com.sails.ai.selfserviceapi.deploypipeline.manifest;

/**
 * Applies to the ingress container only — Cloud Run bills the sum of every container's own
 * resource limits, and poc.yaml has one {@code resources:} block for what its author means as "the
 * whole service." A sidecar gets Cloud Run's own built-in default instead of a value invented here,
 * which avoids landing on a fractional CPU value Cloud Run won't accept. Either field may be null,
 * in which case Cloud Run's own default applies to the ingress container too.
 */
public record Resources(String cpu, String memory) {
}
