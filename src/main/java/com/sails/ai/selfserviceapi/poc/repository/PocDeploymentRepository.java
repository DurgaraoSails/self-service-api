package com.sails.ai.selfserviceapi.poc.repository;

import com.sails.ai.selfserviceapi.poc.entity.PocDeployment;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;

public interface PocDeploymentRepository extends JpaRepository<PocDeployment, UUID> {

    List<PocDeployment> findByPocIdOrderByStartedAtDesc(Long pocId);

    /** One row per poc_id: its most recently started deployment. Powers latestDeploymentStatus on GET /pocs without an N+1. */
    @Query(value = "SELECT DISTINCT ON (poc_id) * FROM poc_deployments WHERE poc_id IN (:pocIds) ORDER BY poc_id, started_at DESC",
            nativeQuery = true)
    List<PocDeployment> findLatestPerPoc(@Param("pocIds") List<Long> pocIds);
}
