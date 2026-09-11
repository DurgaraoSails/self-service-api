package com.sails.ai.selfserviceapi.asset.controller;

import com.sails.ai.selfserviceapi.asset.service.AssetReviewService;
import com.sails.ai.selfserviceapi.generated.api.AssetReviewApi;
import com.sails.ai.selfserviceapi.generated.model.AssetReviewDetailResponse;
import com.sails.ai.selfserviceapi.generated.model.AssetReviewQueuePageResponse;
import com.sails.ai.selfserviceapi.generated.model.AssetReviewerDashboardResponse;
import com.sails.ai.selfserviceapi.generated.model.CreateAssetReviewRequest;
import com.sails.ai.selfserviceapi.security.CurrentUser;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(prefix = "asset-hub", name = "enabled", havingValue = "true")
public class AssetReviewController implements AssetReviewApi {

    private final AssetReviewService assetReviewService;

    public AssetReviewController(AssetReviewService assetReviewService) {
        this.assetReviewService = assetReviewService;
    }

    @Override
    public ResponseEntity<AssetReviewQueuePageResponse> listAssetReviews(List<String> type, Integer maxAgeDays, Integer page, Integer size) {
        CurrentUser.requireInternal();
        CurrentUser.requireAssetReviewer();
        return ResponseEntity.ok(assetReviewService.listAssetReviews(type, maxAgeDays, page, size));
    }

    @Override
    public ResponseEntity<AssetReviewerDashboardResponse> getAssetReviewerDashboard() {
        CurrentUser.requireInternal();
        CurrentUser.requireAssetReviewer();
        return ResponseEntity.ok(assetReviewService.getAssetReviewerDashboard());
    }

    @Override
    public ResponseEntity<AssetReviewDetailResponse> getAssetReviewDetail(UUID revisionId) {
        CurrentUser.requireInternal();
        CurrentUser.requireAssetReviewer();
        return ResponseEntity.ok(assetReviewService.getAssetReviewDetail(revisionId));
    }

    @Override
    public ResponseEntity<AssetReviewDetailResponse> createAssetReviewDecision(UUID revisionId, CreateAssetReviewRequest createAssetReviewRequest) {
        CurrentUser.requireInternal();
        CurrentUser.requireAssetReviewer();
        return ResponseEntity.ok(assetReviewService.createAssetReviewDecision(revisionId, CurrentUser.id(), createAssetReviewRequest));
    }
}
