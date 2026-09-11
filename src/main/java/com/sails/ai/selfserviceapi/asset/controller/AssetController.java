package com.sails.ai.selfserviceapi.asset.controller;

import com.sails.ai.selfserviceapi.asset.service.AssetAiSuggestionService;
import com.sails.ai.selfserviceapi.asset.service.AssetLifecycleService;
import com.sails.ai.selfserviceapi.generated.api.AssetApi;
import com.sails.ai.selfserviceapi.generated.model.ArchiveAssetRequest;
import com.sails.ai.selfserviceapi.generated.model.AssetAiSuggestionResponse;
import com.sails.ai.selfserviceapi.generated.model.AssetDetailResponse;
import com.sails.ai.selfserviceapi.generated.model.AssetEditorResponse;
import com.sails.ai.selfserviceapi.generated.model.AssetEventRequest;
import com.sails.ai.selfserviceapi.generated.model.AssetFacetsResponse;
import com.sails.ai.selfserviceapi.generated.model.AssetFeedbackRequest;
import com.sails.ai.selfserviceapi.generated.model.AssetFeedbackResponse;
import com.sails.ai.selfserviceapi.generated.model.AssetMinePageResponse;
import com.sails.ai.selfserviceapi.generated.model.AssetPageResponse;
import com.sails.ai.selfserviceapi.generated.model.CreateAssetRequest;
import com.sails.ai.selfserviceapi.generated.model.SubmitWorkingRevisionRequest;
import com.sails.ai.selfserviceapi.generated.model.UpdateAssetRevisionRequest;
import com.sails.ai.selfserviceapi.security.CurrentUser;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(prefix = "asset-hub", name = "enabled", havingValue = "true")
public class AssetController implements AssetApi {

    private final AssetLifecycleService assetLifecycleService;
    private final AssetAiSuggestionService assetAiSuggestionService;

    public AssetController(AssetLifecycleService assetLifecycleService, AssetAiSuggestionService assetAiSuggestionService) {
        this.assetLifecycleService = assetLifecycleService;
        this.assetAiSuggestionService = assetAiSuggestionService;
    }

    @Override
    public ResponseEntity<AssetEditorResponse> createAsset(CreateAssetRequest createAssetRequest) {
        CurrentUser.requireInternal();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(assetLifecycleService.createAsset(createAssetRequest, CurrentUser.id()));
    }

    @Override
    public ResponseEntity<AssetDetailResponse> getAsset(UUID assetId) {
        CurrentUser.requireInternal();
        return ResponseEntity.ok(assetLifecycleService.getAsset(assetId));
    }

    @Override
    public ResponseEntity<AssetPageResponse> listAssets(String q, List<String> type, List<String> tag, String ownerId,
                                                          Boolean launchable, Integer page, Integer size) {
        CurrentUser.requireInternal();
        return ResponseEntity.ok(assetLifecycleService.listAssets(q, type, tag, ownerId, launchable, page, size,
                CurrentUser.id()));
    }

    @Override
    public ResponseEntity<AssetFacetsResponse> getAssetFacets(String q, List<String> type, List<String> tag,
                                                                String ownerId, Boolean launchable) {
        CurrentUser.requireInternal();
        return ResponseEntity.ok(assetLifecycleService.getAssetFacets(q, type, tag, ownerId, launchable));
    }

    @Override
    public ResponseEntity<AssetMinePageResponse> listMyAssets(Integer page, Integer size) {
        CurrentUser.requireInternal();
        return ResponseEntity.ok(assetLifecycleService.listMyAssets(CurrentUser.id(), page, size));
    }

    @Override
    public ResponseEntity<AssetEditorResponse> getWorkingRevision(UUID assetId) {
        CurrentUser.requireInternal();
        return ResponseEntity.ok(assetLifecycleService.getWorkingRevision(assetId, CurrentUser.id()));
    }

    @Override
    public ResponseEntity<AssetEditorResponse> createWorkingRevision(UUID assetId) {
        CurrentUser.requireInternal();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(assetLifecycleService.createWorkingRevision(assetId, CurrentUser.id()));
    }

    @Override
    public ResponseEntity<AssetEditorResponse> updateWorkingRevision(UUID assetId, UpdateAssetRevisionRequest updateAssetRevisionRequest) {
        CurrentUser.requireInternal();
        return ResponseEntity.ok(assetLifecycleService.updateWorkingRevision(assetId, CurrentUser.id(), updateAssetRevisionRequest));
    }

    @Override
    public ResponseEntity<AssetEditorResponse> submitWorkingRevision(UUID assetId, SubmitWorkingRevisionRequest submitWorkingRevisionRequest) {
        CurrentUser.requireInternal();
        return ResponseEntity.ok(assetLifecycleService.submitWorkingRevision(assetId, CurrentUser.id(),
                submitWorkingRevisionRequest.getExpectedVersion()));
    }

    @Override
    public ResponseEntity<Void> archiveAsset(UUID assetId, ArchiveAssetRequest archiveAssetRequest) {
        CurrentUser.requireInternal();
        assetLifecycleService.archiveAsset(assetId, CurrentUser.id(), archiveAssetRequest.getExpectedVersion());
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<AssetAiSuggestionResponse> startAssetAiSuggestions(UUID assetId) {
        CurrentUser.requireInternal();
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(assetAiSuggestionService.startSuggestions(assetId, CurrentUser.id()));
    }

    @Override
    public ResponseEntity<AssetAiSuggestionResponse> getLatestAssetAiSuggestion(UUID assetId) {
        CurrentUser.requireInternal();
        return ResponseEntity.ok(assetAiSuggestionService.getLatestSuggestion(assetId, CurrentUser.id()));
    }

    @Override
    public ResponseEntity<AssetFeedbackResponse> putAssetFeedback(UUID assetId, AssetFeedbackRequest assetFeedbackRequest) {
        CurrentUser.requireInternal();
        return ResponseEntity.ok(assetLifecycleService.putFeedback(assetId, CurrentUser.id(), assetFeedbackRequest));
    }

    @Override
    public ResponseEntity<Void> recordAssetEvent(UUID assetId, AssetEventRequest assetEventRequest) {
        CurrentUser.requireInternal();
        assetLifecycleService.recordEvent(assetId, CurrentUser.id(), assetEventRequest);
        return ResponseEntity.noContent().build();
    }
}
