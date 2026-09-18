package com.sails.ai.selfserviceapi.poc.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How long a deployment may sit in a non-terminal status (PENDING/BUILDING/DEPLOYING) with no
 * update before {@code StaleDeploymentReconciler} gives up on it and marks it FAILED. Exists
 * because a crash of self-service-api itself, mid-deployment, leaves the row exactly where it
 * was forever — nothing else ever revisits it (see {@code docs/specs/poc-deployment.md}, "Trigger
 * ordering is not transactionally guaranteed").
 *
 * <p>{@code staleAfter} must comfortably exceed the longest a deployment can legitimately sit at
 * one status while the pipeline is actually healthy — today that's roughly
 * {@code pipeline.build-timeout} at each of the BUILDING and DEPLOYING stages, since
 * {@code BuildService.awaitSuccess} blocks synchronously for up to that long before either stage
 * reports its own next status. Too short a value races the in-process pipeline's own success path
 * and would fail a deployment that was actually about to finish; too long leaves an admin staring
 * at a dead deployment for that much longer before {@code retryDeployment} becomes possible.
 */
@ConfigurationProperties(prefix = "deployment.reconciliation")
public record DeploymentReconciliationProperties(
        boolean enabled,
        Duration staleAfter
) {

    public DeploymentReconciliationProperties {
        if (enabled && (staleAfter == null || staleAfter.isZero() || staleAfter.isNegative())) {
            throw new IllegalArgumentException(
                    "deployment.reconciliation.stale-after must be a positive duration when "
                            + "deployment.reconciliation.enabled is true, but was '" + staleAfter + "'.");
        }
    }
}
