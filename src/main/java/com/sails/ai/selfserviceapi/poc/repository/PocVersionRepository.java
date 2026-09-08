package com.sails.ai.selfserviceapi.poc.repository;

import com.sails.ai.selfserviceapi.poc.entity.PocVersion;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PocVersionRepository extends JpaRepository<PocVersion, UUID> {

    Optional<PocVersion> findTopByPocIdOrderByMajorDescMinorDescPatchDesc(UUID pocId);

    List<PocVersion> findByPocIdOrderByMajorDescMinorDescPatchDesc(UUID pocId);

    List<PocVersion> findByIdIn(List<UUID> ids);
}
