package com.sails.ai.selfserviceapi.poc.repository;

import com.sails.ai.selfserviceapi.poc.entity.PocRepoStatus;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PocRepoStatusRepository extends JpaRepository<PocRepoStatus, UUID> {
}
