package com.sails.ai.selfserviceapi.poc.deployment;

import org.springframework.data.jpa.repository.JpaRepository;

/** Insert-only. The pipeline owns every row after it lands. */
public interface DeploymentJobRepository extends JpaRepository<DeploymentJob, Long> {
}
