package com.sails.ai.selfserviceapi.deploypipeline.manifest;

/**
 * Exactly one container in a manifest must be INGRESS (it receives Cloud Run's $PORT and gets the
 * service's public entry point); any others are SIDECAR (reachable only from the ingress
 * container). Enforced in {@link ManifestValidator}, and again at the database level by
 * {@code uq_pvc_one_ingress_per_version}.
 */
public enum ContainerRole {
    INGRESS,
    SIDECAR
}
