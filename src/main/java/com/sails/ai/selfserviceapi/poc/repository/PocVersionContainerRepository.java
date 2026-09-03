package com.sails.ai.selfserviceapi.poc.repository;

import com.sails.ai.selfserviceapi.poc.entity.PocVersionContainer;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PocVersionContainerRepository extends JpaRepository<PocVersionContainer, Long> {

    List<PocVersionContainer> findByPocVersionId(Long pocVersionId);
}
