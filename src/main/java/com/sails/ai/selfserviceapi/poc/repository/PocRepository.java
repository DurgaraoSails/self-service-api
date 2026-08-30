package com.sails.ai.selfserviceapi.poc.repository;

import com.sails.ai.selfserviceapi.poc.entity.Poc;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PocRepository extends JpaRepository<Poc, Long> {

    List<Poc> findByStatusAndDeletedAtIsNull(String status);

    List<Poc> findByDeletedAtIsNull();

    Optional<Poc> findBySlug(String slug);
}
