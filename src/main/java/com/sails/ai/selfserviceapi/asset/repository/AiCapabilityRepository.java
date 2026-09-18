package com.sails.ai.selfserviceapi.asset.repository;

import com.sails.ai.selfserviceapi.asset.entity.AiCapability;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiCapabilityRepository extends JpaRepository<AiCapability, Long> {

    Optional<AiCapability> findByNormalizedName(String normalizedName);
}
