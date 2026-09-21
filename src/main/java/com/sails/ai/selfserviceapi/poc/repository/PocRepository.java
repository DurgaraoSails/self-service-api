package com.sails.ai.selfserviceapi.poc.repository;

import com.sails.ai.selfserviceapi.poc.entity.Poc;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PocRepository extends JpaRepository<Poc, UUID> {

    List<Poc> findByVisibilityStatusAndDeletedAtIsNull(String visibilityStatus);

    List<Poc> findByDeletedAtIsNull();

    /** Looked up by slug for POST /pocs/{slug}/launch — slugs, not ids, name POCs externally. */
    Optional<Poc> findBySlugAndDeletedAtIsNull(String slug);

    /**
     * Pre-checks the DB's own {@code UNIQUE} constraint before an insert/update reaches it — that
     * constraint has no partial index excluding soft-deleted rows, so a slug stays taken even after
     * its POC is deleted, and this check is deliberately just as strict.
     */
    boolean existsBySlug(String slug);

    /** The repositories the deploy pipeline polls for upstream changes. */
    List<Poc> findByGithubUrlIsNotNullAndDeletedAtIsNull();

    /** POCs that actually can be deployed — deployNewVersion/redeployVersion require both. */
    List<Poc> findByGithubUrlIsNotNullAndSlugIsNotNullAndDeletedAtIsNull();
}
