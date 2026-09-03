package com.sails.ai.selfserviceapi.deploypipeline.manifest;

/**
 * Applied to the ingress container only — Cloud Run bills the SUM of every container's own
 * resource limits, and poc.yaml has one block for what it calls "the whole service." Sidecars get
 * Cloud Run's own built-in default instead of a value invented here, to avoid landing on a
 * fractional CPU value Cloud Run doesn't accept.
 */
public record Resources(String cpu, String memory) {

    public static Resources defaults() {
        return new Resources("1", "512Mi");
    }
}
