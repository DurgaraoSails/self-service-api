package com.sails.ai.selfserviceapi.poc.service;

/**
 * One container's build outcome, reported alongside a deployment's overall status. {@code role}
 * is a plain string ("INGRESS"/"SIDECAR") rather than the pipeline's {@code ContainerRole} enum —
 * this service doesn't depend on deploy-pipeline types, matching how the rest of {@link
 * PocDeploymentService} takes plain strings/primitives from its callers.
 */
public record PocContainerReport(String name, String role, String containerImage, Integer port) {
}
