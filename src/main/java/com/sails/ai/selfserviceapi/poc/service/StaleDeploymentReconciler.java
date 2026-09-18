package com.sails.ai.selfserviceapi.poc.service;

import com.sails.ai.selfserviceapi.poc.config.DeploymentReconciliationProperties;
import com.sails.ai.selfserviceapi.poc.entity.PocDeployment;
import com.sails.ai.selfserviceapi.poc.repository.PocDeploymentRepository;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fails a deployment that has sat in PENDING/BUILDING/DEPLOYING far longer than any healthy
 * pipeline run would — the only thing that ever notices when the process driving it dies
 * mid-flight.
 *
 * <p>{@code PipelineRunner} runs entirely in-process, with no durable record of "this attempt is
 * in flight" beyond the row itself (see its own javadoc: "no webhook, no queue: this runs in the
 * same process"). If self-service-api restarts — a redeploy of the platform itself, an OOM kill, a
 * Cloud Run instance eviction — while a deployment is between status updates, its {@code catch}
 * block that would otherwise mark it FAILED never runs, because there is no process left to run
 * it. The row is then stuck at whatever status it last reached, forever: {@code
 * requireNoActiveDeployment} treats it as still in progress, so it also permanently blocks every
 * future deploy/redeploy for that POC, and {@code retryDeployment} only accepts an already-FAILED
 * deployment, so there is no way to unstick it through the API either. See
 * {@code docs/specs/poc-deployment.md}, "Trigger ordering is not transactionally guaranteed", for
 * the gap this closes.
 *
 * <p>Deliberately a timeout, not a resumption: nothing durable records which Cloud Build job (if
 * any) a stuck row was waiting on, so there is nothing to resume from — only enough to say the
 * attempt is dead and let a fresh retry start over.
 */
@Component
public class StaleDeploymentReconciler {

    private static final Logger log = LoggerFactory.getLogger(StaleDeploymentReconciler.class);

    private static final String FAILED = "FAILED";
    private static final List<String> IN_PROGRESS_STATUSES = List.of("PENDING", "BUILDING", "DEPLOYING");

    private final PocDeploymentRepository pocDeploymentRepository;
    private final DeploymentReconciliationProperties properties;

    public StaleDeploymentReconciler(PocDeploymentRepository pocDeploymentRepository,
                                      DeploymentReconciliationProperties properties) {
        this.pocDeploymentRepository = pocDeploymentRepository;
        this.properties = properties;
    }

    @Scheduled(initialDelay = 30_000, fixedDelayString = "${deployment.reconciliation.poll-interval-ms:300000}")
    public void reconcile() {
        if (!properties.enabled()) {
            return;
        }
        Instant cutoff = Instant.now().minus(properties.staleAfter());
        List<PocDeployment> stale = pocDeploymentRepository.findByStatusInAndUpdatedAtBefore(IN_PROGRESS_STATUSES, cutoff);
        for (PocDeployment deployment : stale) {
            try {
                failStale(deployment);
            } catch (Exception e) {
                // One row's unexpected failure (a transient DB error, a concurrent update) must
                // not stop the rest of this sweep — the next scheduled run picks up whatever is
                // still stale, including this one.
                log.error("Could not reconcile stale deployment {} — it will be retried on the next sweep",
                        deployment.getId(), e);
            }
        }
    }

    /**
     * One {@code save()} per stale row rather than one transaction for the whole sweep — each
     * write is already transactional on its own (Spring Data's repository proxy wraps {@code
     * save()}), so a failure on one stale row (an unexpected constraint, a transient DB error)
     * cannot roll back or block the rest of the sweep from being reconciled too.
     */
    void failStale(PocDeployment deployment) {
        log.warn("Deployment {} for poc {} has been {} since {} with no update (past the {} staleness threshold) — "
                        + "marking it FAILED so it can be retried. This usually means self-service-api itself "
                        + "restarted mid-deployment.",
                deployment.getId(), deployment.getPocId(), deployment.getStatus(), deployment.getUpdatedAt(),
                properties.staleAfter());
        deployment.setStatus(FAILED);
        deployment.setErrorMessage("No status update was received for over " + properties.staleAfter()
                + " — the platform likely restarted mid-deployment. Retry to try again.");
        deployment.setContainerProgress(null);
        deployment.setCompletedAt(Instant.now());
        pocDeploymentRepository.save(deployment);
    }
}
