package com.sails.ai.selfserviceapi.poc.repository;

import com.sails.ai.selfserviceapi.poc.entity.PocVersionContainer;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PocVersionContainerRepository extends JpaRepository<PocVersionContainer, Long> {

    List<PocVersionContainer> findByPocVersionId(UUID pocVersionId);

    /** Batch lookup for GET /pocs/{id}/versions — mirrors PocVersionRepository.findByIdIn's pattern. */
    List<PocVersionContainer> findByPocVersionIdIn(List<UUID> pocVersionIds);

    /** Idempotency for a retried build on the same version — replace rather than accumulate duplicates. */
    void deleteByPocVersionId(UUID pocVersionId);
}
