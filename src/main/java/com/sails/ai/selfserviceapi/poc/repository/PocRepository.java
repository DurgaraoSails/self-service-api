package com.sails.ai.selfserviceapi.poc.repository;

import com.sails.ai.selfserviceapi.poc.entity.Poc;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PocRepository extends JpaRepository<Poc, Long> {

    List<Poc> findByVisibilityStatusAndDeletedAtIsNull(String visibilityStatus);

    List<Poc> findByDeletedAtIsNull();

    /** The repositories the deploy pipeline polls for upstream changes. */
    List<Poc> findByGithubUrlIsNotNullAndDeletedAtIsNull();
}
