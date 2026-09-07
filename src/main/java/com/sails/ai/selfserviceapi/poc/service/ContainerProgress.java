package com.sails.ai.selfserviceapi.poc.service;

/**
 * One container's static progress snapshot for a single deployment attempt, serialized as JSON
 * into {@code poc_deployments.container_progress}. Coarser than true live per-container progress —
 * every container flips state together, since a single Cloud Build job reports one terminal status
 * for the whole multi-step build and one for the whole multi-container deploy — but it's an honest
 * reflection of what that single job actually tells us, without parsing build logs.
 *
 * <p>{@code state} is one of PENDING (manifest resolved, not yet built), BUILT (images pushed,
 * not yet deployed), DEPLOYED (the service is live). There is no FAILED state here — a failed
 * deployment clears containerProgress entirely instead (see {@code PocDeploymentService.reportStatus}).
 */
public record ContainerProgress(String name, String role, String state, String containerImage, Integer port) {
}
