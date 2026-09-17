package com.sails.ai.selfserviceapi.asset.repository;

import com.sails.ai.selfserviceapi.asset.entity.AssetRevision;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AssetRevisionRepository extends JpaRepository<AssetRevision, UUID> {

    /**
     * Types filter is a comma-separated string, not a {@code List<String>} bound to {@code IN} —
     * Hibernate binds a null collection parameter for an {@code IN} clause at prepare time, before
     * the {@code :types is null} guard ever short-circuits, so a null list throws regardless of the
     * OR. Same fix as {@link AssetSearchRepository}'s native queries, applied here as a native query
     * too for consistency (and because it needs the {@code assets} join anyway).
     *
     * <p>Every {@code (:param is null or ...)} guard also needs an explicit {@code cast(:param as
     * ...)} on the {@code is null} check itself: Postgres resolves each bind placeholder's type
     * independently at prepare time, and a placeholder whose only occurrence is a bare
     * {@code IS NULL} gives it nothing to infer from — {@code ERROR: could not determine data type
     * of parameter $N} — even though the same named parameter has clear type context elsewhere in
     * the query.
     */
    @Query(value = """
            select r.* from asset_revisions r
            join assets a on a.id = r.asset_id
            where r.state = 'PENDING_REVIEW'
              and (cast(:typesCsv as text) is null or a.asset_type = any(string_to_array(:typesCsv, ',')))
              and (cast(:cutoff as timestamptz) is null or r.submitted_at <= :cutoff)
            order by r.submitted_at asc, r.id asc
            limit :limit offset :offset
            """, nativeQuery = true)
    List<AssetRevision> findPendingQueue(@Param("typesCsv") String typesCsv,
                                          @Param("cutoff") Instant cutoff,
                                          @Param("limit") int limit,
                                          @Param("offset") int offset);

    @Query(value = """
            select count(*) from asset_revisions r
            join assets a on a.id = r.asset_id
            where r.state = 'PENDING_REVIEW'
              and (cast(:typesCsv as text) is null or a.asset_type = any(string_to_array(:typesCsv, ',')))
              and (cast(:cutoff as timestamptz) is null or r.submitted_at <= :cutoff)
            """, nativeQuery = true)
    long countPendingQueue(@Param("typesCsv") String typesCsv, @Param("cutoff") Instant cutoff);

    List<AssetRevision> findByAssetIdOrderByRevisionNumberDesc(UUID assetId);
}
