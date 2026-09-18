package com.sails.ai.selfserviceapi.poc.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sails.ai.selfserviceapi.poc.config.DeploymentReconciliationProperties;
import com.sails.ai.selfserviceapi.poc.entity.PocDeployment;
import com.sails.ai.selfserviceapi.poc.repository.PocDeploymentRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Covers the crash-recovery gap {@code docs/specs/poc-deployment.md} calls out: a deployment left
 * in PENDING/BUILDING/DEPLOYING by a self-service-api crash mid-attempt has nothing else that will
 * ever revisit it. Mirrors PocDeploymentServiceTest's plain-Mockito style — no Spring context.
 */
class StaleDeploymentReconcilerTest {

    private final PocDeploymentRepository pocDeploymentRepository = mock(PocDeploymentRepository.class);

    @Test
    void marksAStaleInProgressDeploymentFailedWithARetryableExplanation() {
        DeploymentReconciliationProperties properties = new DeploymentReconciliationProperties(true, Duration.ofHours(1));
        StaleDeploymentReconciler reconciler = new StaleDeploymentReconciler(pocDeploymentRepository, properties);

        PocDeployment stuck = stuckDeployment("PENDING", Instant.now().minus(Duration.ofHours(2)));
        when(pocDeploymentRepository.findByStatusInAndUpdatedAtBefore(
                eq(List.of("PENDING", "BUILDING", "DEPLOYING")), any(Instant.class)))
                .thenReturn(List.of(stuck));
        when(pocDeploymentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        reconciler.reconcile();

        assertThat(stuck.getStatus()).isEqualTo("FAILED");
        assertThat(stuck.getErrorMessage()).contains("restarted mid-deployment").contains("Retry");
        assertThat(stuck.getContainerProgress()).isNull();
        assertThat(stuck.getCompletedAt()).isNotNull();
        verify(pocDeploymentRepository).save(stuck);
    }

    @Test
    void reconcilesEveryStaleRowFoundEvenWhenThereAreSeveral() {
        DeploymentReconciliationProperties properties = new DeploymentReconciliationProperties(true, Duration.ofHours(1));
        StaleDeploymentReconciler reconciler = new StaleDeploymentReconciler(pocDeploymentRepository, properties);

        PocDeployment first = stuckDeployment("BUILDING", Instant.now().minus(Duration.ofHours(3)));
        PocDeployment second = stuckDeployment("DEPLOYING", Instant.now().minus(Duration.ofHours(4)));
        when(pocDeploymentRepository.findByStatusInAndUpdatedAtBefore(anyList(), any(Instant.class)))
                .thenReturn(List.of(first, second));
        when(pocDeploymentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        reconciler.reconcile();

        assertThat(first.getStatus()).isEqualTo("FAILED");
        assertThat(second.getStatus()).isEqualTo("FAILED");
    }

    /** A row failing to save must not stop the rest of the sweep from being reconciled. */
    @Test
    void aFailureReconcilingOneRowDoesNotStopTheRestOfTheSweep() {
        DeploymentReconciliationProperties properties = new DeploymentReconciliationProperties(true, Duration.ofHours(1));
        StaleDeploymentReconciler reconciler = new StaleDeploymentReconciler(pocDeploymentRepository, properties);

        PocDeployment broken = stuckDeployment("PENDING", Instant.now().minus(Duration.ofHours(2)));
        PocDeployment healthy = stuckDeployment("BUILDING", Instant.now().minus(Duration.ofHours(2)));
        when(pocDeploymentRepository.findByStatusInAndUpdatedAtBefore(anyList(), any(Instant.class)))
                .thenReturn(List.of(broken, healthy));
        when(pocDeploymentRepository.save(broken)).thenThrow(new RuntimeException("transient DB error"));
        when(pocDeploymentRepository.save(healthy)).thenAnswer(invocation -> invocation.getArgument(0));

        reconciler.reconcile();

        assertThat(healthy.getStatus()).isEqualTo("FAILED");
    }

    @Test
    void doesNothingWhenReconciliationIsDisabled() {
        DeploymentReconciliationProperties properties = new DeploymentReconciliationProperties(false, null);
        StaleDeploymentReconciler reconciler = new StaleDeploymentReconciler(pocDeploymentRepository, properties);

        reconciler.reconcile();

        verify(pocDeploymentRepository, never()).findByStatusInAndUpdatedAtBefore(anyList(), any());
        verify(pocDeploymentRepository, never()).save(any());
    }

    @Test
    void doesNothingWhenNoDeploymentIsStale() {
        DeploymentReconciliationProperties properties = new DeploymentReconciliationProperties(true, Duration.ofHours(1));
        StaleDeploymentReconciler reconciler = new StaleDeploymentReconciler(pocDeploymentRepository, properties);
        when(pocDeploymentRepository.findByStatusInAndUpdatedAtBefore(anyList(), any(Instant.class))).thenReturn(List.of());

        reconciler.reconcile();

        verify(pocDeploymentRepository, never()).save(any());
    }

    private static PocDeployment stuckDeployment(String status, Instant updatedAt) {
        PocDeployment deployment = new PocDeployment();
        deployment.setId(UUID.randomUUID());
        deployment.setPocId(UUID.randomUUID());
        deployment.setPocVersionId(UUID.randomUUID());
        deployment.setKind("BUILD_AND_DEPLOY");
        deployment.setStatus(status);
        deployment.setStartedAt(updatedAt);
        deployment.setUpdatedAt(updatedAt);
        return deployment;
    }
}
