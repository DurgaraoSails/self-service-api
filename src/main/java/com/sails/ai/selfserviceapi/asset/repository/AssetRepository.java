package com.sails.ai.selfserviceapi.asset.repository;

import com.sails.ai.selfserviceapi.asset.entity.Asset;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AssetRepository extends JpaRepository<Asset, UUID> {

    List<Asset> findByOwnerUserIdAndArchivedAtIsNull(String ownerUserId);

    List<Asset> findByArchivedAtIsNull();

    /** Numerator for the Metrics Contract's contributor-adoption calculation. */
    @Query("select count(distinct a.submittedByUserId) from Asset a where a.createdAt >= :since")
    long countDistinctSubmittersSince(@Param("since") Instant since);

    /**
     * Serializes every mutating operation on this asset — edits, submit, archive, and review
     * decisions all lock here first. A decision also updates approvedRevisionId/workingRevisionId,
     * so this is what makes "two reviewers can't both decide it" hold.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Asset a where a.id = :id")
    Optional<Asset> lockById(UUID id);
}
