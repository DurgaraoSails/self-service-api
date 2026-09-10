package com.sails.ai.selfserviceapi.asset.repository;

import com.sails.ai.selfserviceapi.asset.entity.AssetReview;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssetReviewRepository extends JpaRepository<AssetReview, UUID> {
}
