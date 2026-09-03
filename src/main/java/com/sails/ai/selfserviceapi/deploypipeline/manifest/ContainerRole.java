package com.sails.ai.selfserviceapi.deploypipeline.manifest;

/** Exactly one container per manifest must be INGRESS; the rest are SIDECARs. */
public enum ContainerRole {
    INGRESS,
    SIDECAR
}
