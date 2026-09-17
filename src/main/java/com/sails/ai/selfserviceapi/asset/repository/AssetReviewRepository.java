package com.sails.ai.selfserviceapi.asset.repository;

import com.sails.ai.selfserviceapi.asset.entity.AssetReview;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AssetReviewRepository extends JpaRepository<AssetReview, UUID> {

    List<AssetReview> findByRevisionIdOrderByCreatedAtAsc(UUID revisionId);

    Optional<AssetReview> findFirstByRevisionIdOrderByCreatedAtDesc(UUID revisionId);

    /**
     * One row: {@code (medianSeconds, p90Seconds)} per the Metrics Contract's review-turnaround
     * definition — each reviewed revision's most recent decision minus its {@code submitted_at}.
     * Revisions never submitted, or never yet decided, are excluded. Both columns are {@code null}
     * when no revision has a decision yet.
     */
    @Query(value = """
            select
              percentile_cont(0.5) within group (order by extract(epoch from (latest.created_at - ar.submitted_at))),
              percentile_cont(0.9) within group (order by extract(epoch from (latest.created_at - ar.submitted_at)))
            from asset_revisions ar
            join lateral (
                select rev.created_at
                from asset_reviews rev
                where rev.revision_id = ar.id
                order by rev.created_at desc
                limit 1
            ) latest on true
            where ar.submitted_at is not null
            """, nativeQuery = true)
    List<Object[]> findReviewTurnaroundSecondsStats();
}
