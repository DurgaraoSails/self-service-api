package com.sails.ai.selfserviceapi.deployment.repository;

import com.sails.ai.selfserviceapi.deployment.entity.PocDeployment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PocDeploymentRepository extends JpaRepository<PocDeployment, Long> {
}
