package com.sails.ai.selfserviceapi.deployment.repository;

import com.sails.ai.selfserviceapi.deployment.entity.PocDeployment;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PocDeploymentRepository extends JpaRepository<PocDeployment, Long> {

    List<PocDeployment> findByStatus(String status);

    Optional<PocDeployment> findFirstByPocIdAndStatusOrderByCreatedAtDesc(Long pocId, String status);

    Optional<PocDeployment> findFirstByPocIdOrderByCreatedAtDesc(Long pocId);

    boolean existsByPocIdAndStatus(Long pocId, String status);

    /**
     * Every deployment for the given POCs, newest first — the caller keeps the first row per
     * poc_id to get "latest per POC". Deliberately one query for the whole set rather than a
     * per-POC lookup, so the fleet view doesn't fan out into N+1 queries as POCs are added.
     */
    List<PocDeployment> findByPocIdInOrderByCreatedAtDesc(Collection<Long> pocIds);
}
