package com.sails.ai.selfserviceapi.asset.repository;

import com.sails.ai.selfserviceapi.asset.entity.AssetReview;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssetReviewRepository extends JpaRepository<AssetReview, UUID> {

    List<AssetReview> findByRevisionIdOrderByCreatedAtAsc(UUID revisionId);

    Optional<AssetReview> findFirstByRevisionIdOrderByCreatedAtDesc(UUID revisionId);
}
