package com.sails.ai.selfserviceapi.poc.repository;

import com.sails.ai.selfserviceapi.poc.entity.PocVersion;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PocVersionRepository extends JpaRepository<PocVersion, UUID> {

    Optional<PocVersion> findTopByPocIdOrderByMajorDescMinorDescPatchDesc(UUID pocId);

    List<PocVersion> findByPocIdOrderByMajorDescMinorDescPatchDesc(UUID pocId);

    /** Find-or-create key for deploying an existing tag — version_label is the tag name, unique per POC. */
    Optional<PocVersion> findByPocIdAndVersionLabel(UUID pocId, String versionLabel);

    List<PocVersion> findByIdIn(List<UUID> ids);
}
