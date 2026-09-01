package com.sails.ai.selfserviceapi.poc.repository;

import com.sails.ai.selfserviceapi.poc.entity.PocVersion;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PocVersionRepository extends JpaRepository<PocVersion, Long> {

    Optional<PocVersion> findTopByPocIdOrderByMajorDescMinorDescPatchDesc(Long pocId);

    List<PocVersion> findByPocIdOrderByMajorDescMinorDescPatchDesc(Long pocId);

    List<PocVersion> findByIdIn(List<Long> ids);
}
