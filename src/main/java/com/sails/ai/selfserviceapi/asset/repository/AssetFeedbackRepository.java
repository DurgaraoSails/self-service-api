package com.sails.ai.selfserviceapi.asset.repository;

import com.sails.ai.selfserviceapi.asset.entity.AssetFeedback;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssetFeedbackRepository extends JpaRepository<AssetFeedback, UUID> {

    Optional<AssetFeedback> findByAssetIdAndUserId(UUID assetId, String userId);
}
