package com.sails.ai.selfserviceapi.poc.repository;

import com.sails.ai.selfserviceapi.poc.entity.Poc;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PocRepository extends JpaRepository<Poc, Long> {

    List<Poc> findByVisibilityStatusAndDeletedAtIsNull(String visibilityStatus);

    List<Poc> findByDeletedAtIsNull();

    /** The repositories the deploy pipeline polls for upstream changes. */
    List<Poc> findByGithubUrlIsNotNullAndDeletedAtIsNull();

    /** POCs that actually can be deployed — deployNewVersion/redeployVersion require both. */
    List<Poc> findByGithubUrlIsNotNullAndSlugIsNotNullAndDeletedAtIsNull();

    /** The launch endpoint's lookup: slug is unique, and a deleted POC is not launchable. */
    Optional<Poc> findBySlugAndDeletedAtIsNull(String slug);
}
