package com.sails.ai.selfserviceapi.asset.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sails.ai.selfserviceapi.asset.entity.Asset;
import com.sails.ai.selfserviceapi.asset.entity.AssetRevision;
import com.sails.ai.selfserviceapi.asset.exception.AssetRevisionStaleException;
import com.sails.ai.selfserviceapi.asset.exception.InvalidAssetTransitionException;
import com.sails.ai.selfserviceapi.asset.exception.SelfReviewForbiddenException;
import com.sails.ai.selfserviceapi.asset.repository.AssetRepository;
import com.sails.ai.selfserviceapi.asset.repository.AssetReviewRepository;
import com.sails.ai.selfserviceapi.asset.repository.AssetRevisionRepository;
import com.sails.ai.selfserviceapi.asset.search.AssetSearchIndexer;
import com.sails.ai.selfserviceapi.common.exception.ApiException;
import com.sails.ai.selfserviceapi.generated.model.AssetReviewDetailResponse;
import com.sails.ai.selfserviceapi.generated.model.AssetReviewerDashboardResponse;
import com.sails.ai.selfserviceapi.generated.model.CreateAssetReviewRequest;
import com.sails.ai.selfserviceapi.user.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class AssetReviewServiceTest {

    private AssetRepository assetRepository;
    private AssetRevisionRepository assetRevisionRepository;
    private AssetReviewRepository assetReviewRepository;
    private UserRepository userRepository;
    private AssetSearchIndexer assetSearchIndexer;
    private AssetReviewService service;

    @BeforeEach
    void setUp() {
        assetRepository = Mockito.mock(AssetRepository.class);
        assetRevisionRepository = Mockito.mock(AssetRevisionRepository.class);
        assetReviewRepository = Mockito.mock(AssetReviewRepository.class);
        userRepository = Mockito.mock(UserRepository.class);
        assetSearchIndexer = Mockito.mock(AssetSearchIndexer.class);
        service = new AssetReviewService(assetRepository, assetRevisionRepository, assetReviewRepository,
                userRepository, assetSearchIndexer);
    }

    @Test
    void blocksSelfReviewBySubmitter() {
        Asset asset = asset("submitter-1", "owner-1");
        AssetRevision revision = pendingRevision(asset.getId(), "author-1");
        stubLockedRevision(asset, revision);

        assertThatThrownBy(() -> service.createAssetReviewDecision(revision.getId(), "submitter-1",
                decisionRequest("APPROVE", null, revision.getVersion())))
                .isInstanceOf(SelfReviewForbiddenException.class);
    }

    @Test
    void blocksSelfReviewByRevisionAuthor() {
        Asset asset = asset("submitter-1", "owner-1");
        AssetRevision revision = pendingRevision(asset.getId(), "author-1");
        stubLockedRevision(asset, revision);

        assertThatThrownBy(() -> service.createAssetReviewDecision(revision.getId(), "author-1",
                decisionRequest("APPROVE", null, revision.getVersion())))
                .isInstanceOf(SelfReviewForbiddenException.class);
    }

    @Test
    void rejectsADecisionOnARevisionThatIsNoLongerPending() {
        Asset asset = asset("submitter-1", "owner-1");
        AssetRevision revision = pendingRevision(asset.getId(), "author-1");
        revision.setState(AssetRevision.APPROVED);
        stubLockedRevision(asset, revision);

        assertThatThrownBy(() -> service.createAssetReviewDecision(revision.getId(), "reviewer-1",
                decisionRequest("APPROVE", null, revision.getVersion())))
                .isInstanceOf(InvalidAssetTransitionException.class);
    }

    @Test
    void rejectsAStaleExpectedVersion() {
        Asset asset = asset("submitter-1", "owner-1");
        AssetRevision revision = pendingRevision(asset.getId(), "author-1");
        stubLockedRevision(asset, revision);

        assertThatThrownBy(() -> service.createAssetReviewDecision(revision.getId(), "reviewer-1",
                decisionRequest("APPROVE", null, revision.getVersion() + 1)))
                .isInstanceOf(AssetRevisionStaleException.class);
    }

    @Test
    void requiresFeedbackForRequestChangesAndReject() {
        Asset asset = asset("submitter-1", "owner-1");
        AssetRevision revision = pendingRevision(asset.getId(), "author-1");
        stubLockedRevision(asset, revision);

        assertThatThrownBy(() -> service.createAssetReviewDecision(revision.getId(), "reviewer-1",
                decisionRequest("REJECT", null, revision.getVersion())))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("feedback is required");
    }

    @Test
    void approvePromotesTheRevisionSupersedesThePriorApprovedRevisionAndReindexes() {
        Asset asset = asset("submitter-1", "owner-1");
        AssetRevision priorApproved = new AssetRevision();
        priorApproved.setId(UUID.randomUUID());
        priorApproved.setState(AssetRevision.APPROVED);
        asset.setApprovedRevisionId(priorApproved.getId());
        AssetRevision revision = pendingRevision(asset.getId(), "author-1");
        stubLockedRevision(asset, revision);
        when(assetRevisionRepository.findById(priorApproved.getId())).thenReturn(Optional.of(priorApproved));
        when(assetReviewRepository.findByRevisionIdOrderByCreatedAtAsc(revision.getId())).thenReturn(List.of());

        service.createAssetReviewDecision(revision.getId(), "reviewer-1",
                decisionRequest("APPROVE", null, revision.getVersion()));

        assertThat(priorApproved.getState()).isEqualTo(AssetRevision.SUPERSEDED);
        assertThat(revision.getState()).isEqualTo(AssetRevision.APPROVED);
        assertThat(asset.getApprovedRevisionId()).isEqualTo(revision.getId());
        assertThat(asset.getWorkingRevisionId()).isNull();
        verify(assetSearchIndexer).index(asset, revision, null);
    }

    @Test
    void requestChangesAndRejectDoNotReindexOrTouchApprovedRevisionPointer() {
        Asset asset = asset("submitter-1", "owner-1");
        AssetRevision revision = pendingRevision(asset.getId(), "author-1");
        stubLockedRevision(asset, revision);
        when(assetReviewRepository.findByRevisionIdOrderByCreatedAtAsc(revision.getId())).thenReturn(List.of());

        service.createAssetReviewDecision(revision.getId(), "reviewer-1",
                decisionRequest("REQUEST_CHANGES", "please fix the summary", revision.getVersion()));

        assertThat(revision.getState()).isEqualTo(AssetRevision.CHANGES_REQUESTED);
        verify(assetSearchIndexer, never()).index(any(), any(), any());
    }

    @Test
    void appendsAReviewRowRatherThanUpdatingAnExistingOne() {
        Asset asset = asset("submitter-1", "owner-1");
        AssetRevision revision = pendingRevision(asset.getId(), "author-1");
        stubLockedRevision(asset, revision);
        when(assetReviewRepository.findByRevisionIdOrderByCreatedAtAsc(revision.getId())).thenReturn(List.of());

        service.createAssetReviewDecision(revision.getId(), "reviewer-1",
                decisionRequest("REJECT", "not aligned", revision.getVersion()));

        ArgumentCaptor<com.sails.ai.selfserviceapi.asset.entity.AssetReview> saved =
                ArgumentCaptor.forClass(com.sails.ai.selfserviceapi.asset.entity.AssetReview.class);
        verify(assetReviewRepository).save(saved.capture());
        assertThat(saved.getValue().getRevisionId()).isEqualTo(revision.getId());
        assertThat(saved.getValue().getReviewerUserId()).isEqualTo("reviewer-1");
        assertThat(saved.getValue().getDecision()).isEqualTo("REJECT");
        assertThat(saved.getValue().getFeedback()).isEqualTo("not aligned");
    }

    @Test
    void dashboardCountsExcludeArchivedAssetsAndIncludeZeroCountCategories() {
        Asset approvedAsset = asset("submitter-1", "owner-1");
        approvedAsset.setAssetType("POC");
        approvedAsset.setApprovedRevisionId(UUID.randomUUID());
        when(assetRepository.findByArchivedAtIsNull()).thenReturn(List.of(approvedAsset));
        when(assetRevisionRepository.findAllById(any())).thenReturn(List.of());

        AssetReviewerDashboardResponse response = service.getAssetReviewerDashboard();

        assertThat(response.getTotalAssets()).isEqualTo(1L);
        assertThat(response.getApprovedAssets()).isEqualTo(1L);
        assertThat(response.getInReviewAssets()).isEqualTo(0L);
        assertThat(response.getCategories()).hasSize(3);
        assertThat(response.getCategories().stream()
                .filter(c -> c.getAssetType().getValue().equals("BLOG"))
                .findFirst().orElseThrow().getTotalAssets()).isEqualTo(0L);
    }

    private static Asset asset(String submitterId, String ownerId) {
        Asset asset = new Asset();
        asset.setId(UUID.randomUUID());
        asset.setSubmittedByUserId(submitterId);
        asset.setOwnerUserId(ownerId);
        asset.setAssetType("POC");
        return asset;
    }

    private static AssetRevision pendingRevision(UUID assetId, String authorId) {
        AssetRevision revision = new AssetRevision();
        revision.setId(UUID.randomUUID());
        revision.setAssetId(assetId);
        revision.setState(AssetRevision.PENDING_REVIEW);
        revision.setAuthoredByUserId(authorId);
        revision.setVersion(1L);
        return revision;
    }

    private void stubLockedRevision(Asset asset, AssetRevision revision) {
        when(assetRevisionRepository.findById(revision.getId())).thenReturn(Optional.of(revision));
        when(assetRepository.lockById(asset.getId())).thenReturn(Optional.of(asset));
    }

    private static CreateAssetReviewRequest decisionRequest(String decision, String feedback, Long expectedVersion) {
        return new CreateAssetReviewRequest(CreateAssetReviewRequest.DecisionEnum.fromValue(decision), expectedVersion)
                .feedback(feedback);
    }
}
