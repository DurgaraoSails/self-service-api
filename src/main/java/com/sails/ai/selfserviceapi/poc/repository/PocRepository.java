package com.sails.ai.selfserviceapi.poc.repository;

import com.sails.ai.selfserviceapi.poc.entity.Poc;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PocRepository extends JpaRepository<Poc, Long> {
}
