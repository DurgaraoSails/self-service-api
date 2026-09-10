package com.sails.ai.selfserviceapi.poc.repository;

import com.sails.ai.selfserviceapi.poc.entity.PocRepoTag;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PocRepoTagRepository extends JpaRepository<PocRepoTag, Long> {

    List<PocRepoTag> findByPocIdOrderByPositionAsc(UUID pocId);

    /** Idempotency for a retried/repeated refresh — replace rather than accumulate or diff. */
    void deleteByPocId(UUID pocId);
}
