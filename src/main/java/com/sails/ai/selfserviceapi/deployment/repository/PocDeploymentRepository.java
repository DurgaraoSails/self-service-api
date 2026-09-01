package com.sails.ai.selfserviceapi.deployment.repository;

import com.sails.ai.selfserviceapi.deployment.entity.PocDeployment;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PocDeploymentRepository extends JpaRepository<PocDeployment, Long> {

    List<PocDeployment> findByStatus(String status);

    Optional<PocDeployment> findFirstByPocIdAndStatusOrderByCreatedAtDesc(Long pocId, String status);

    Optional<PocDeployment> findFirstByPocIdOrderByCreatedAtDesc(Long pocId);
}
