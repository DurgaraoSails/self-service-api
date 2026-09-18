package com.sails.ai.selfserviceapi.poc.repository;

import com.sails.ai.selfserviceapi.poc.entity.PocDeployment;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;

public interface PocDeploymentRepository extends JpaRepository<PocDeployment, UUID> {

    List<PocDeployment> findByPocIdOrderByStartedAtDesc(UUID pocId);

    /** The one deployment a retry is allowed to target — an older FAILED one that's since been superseded is not retryable. */
    Optional<PocDeployment> findTopByPocIdOrderByStartedAtDesc(UUID pocId);

    /** Powers the one-active-deployment-per-POC rule. */
    boolean existsByPocIdAndStatusIn(UUID pocId, List<String> statuses);

    /** Powers {@code StaleDeploymentReconciler} — rows still in flight with no update since before the cutoff. */
    List<PocDeployment> findByStatusInAndUpdatedAtBefore(List<String> statuses, Instant cutoff);

    /** One row per poc_id: its most recently started deployment. Powers latestDeploymentStatus on GET /pocs without an N+1. */
    @Query(value = "SELECT DISTINCT ON (poc_id) * FROM poc_deployments WHERE poc_id IN (:pocIds) ORDER BY poc_id, started_at DESC",
            nativeQuery = true)
    List<PocDeployment> findLatestPerPoc(@Param("pocIds") List<UUID> pocIds);
}
