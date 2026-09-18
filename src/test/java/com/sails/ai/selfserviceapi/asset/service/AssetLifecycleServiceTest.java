package com.sails.ai.selfserviceapi.asset.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sails.ai.selfserviceapi.asset.ai.EmbeddingProvider;
import com.sails.ai.selfserviceapi.asset.entity.Asset;
import com.sails.ai.selfserviceapi.asset.entity.AssetEvent;
import com.sails.ai.selfserviceapi.asset.entity.AssetFeedback;
import com.sails.ai.selfserviceapi.asset.entity.AssetRevision;
import com.sails.ai.selfserviceapi.asset.exception.AssetNotFoundException;
import com.sails.ai.selfserviceapi.asset.exception.AssetRevisionStaleException;
import com.sails.ai.selfserviceapi.asset.exception.InvalidAssetTransitionException;
import com.sails.ai.selfserviceapi.asset.exception.WorkingRevisionExistsException;
import com.sails.ai.selfserviceapi.asset.repository.AssetEventRepository;
import com.sails.ai.selfserviceapi.asset.repository.AssetFeedbackRepository;
import com.sails.ai.selfserviceapi.asset.repository.AssetRepository;
import com.sails.ai.selfserviceapi.asset.repository.AssetReviewRepository;
import com.sails.ai.selfserviceapi.asset.repository.AssetRevisionRepository;
import com.sails.ai.selfserviceapi.asset.repository.AssetSearchRepository;
import com.sails.ai.selfserviceapi.asset.repository.TagRepository;
import com.sails.ai.selfserviceapi.asset.search.AssetSearchIndexer;
import com.sails.ai.selfserviceapi.asset.search.AssetSearchRankingService;
import com.sails.ai.selfserviceapi.common.exception.ApiException;
import com.sails.ai.selfserviceapi.generated.model.ArchiveAssetRequest;
import com.sails.ai.selfserviceapi.generated.model.AssetDetailResponse;
import com.sails.ai.selfserviceapi.generated.model.AssetEditorResponse;
import com.sails.ai.selfserviceapi.generated.model.AssetEventRequest;
import com.sails.ai.selfserviceapi.generated.model.AssetFeedbackRequest;
import com.sails.ai.selfserviceapi.generated.model.AssetFeedbackResponse;
import com.sails.ai.selfserviceapi.generated.model.AssetMinePageResponse;
import com.sails.ai.selfserviceapi.generated.model.AssetPageResponse;
import com.sails.ai.selfserviceapi.generated.model.CreateAssetRequest;
import com.sails.ai.selfserviceapi.generated.model.UpdateAssetRevisionRequest;
import com.sails.ai.selfserviceapi.poc.repository.PocRepository;
import com.sails.ai.selfserviceapi.user.entity.AccountType;
import com.sails.ai.selfserviceapi.user.entity.User;
import com.sails.ai.selfserviceapi.user.entity.UserStatus;
import com.sails.ai.selfserviceapi.user.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class AssetLifecycleServiceTest {

    private AssetRepository assetRepository;
    private AssetRevisionRepository assetRevisionRepository;
    private AssetSearchRepository assetSearchRepository;
    private TagRepository tagRepository;
    private UserRepository userRepository;
    private PocRepository pocRepository;
    private AssetFeedbackRepository assetFeedbackRepository;
    private AssetEventRepository assetEventRepository;
    private AssetReviewRepository assetReviewRepository;
    private AssetSearchIndexer assetSearchIndexer;
    private AssetLifecycleService service;

    @BeforeEach
    void setUp() {
        assetRepository = Mockito.mock(AssetRepository.class);
        assetRevisionRepository = Mockito.mock(AssetRevisionRepository.class);
        assetSearchRepository = Mockito.mock(AssetSearchRepository.class);
        tagRepository = Mockito.mock(TagRepository.class);
        userRepository = Mockito.mock(UserRepository.class);
        pocRepository = Mockito.mock(PocRepository.class);
        assetFeedbackRepository = Mockito.mock(AssetFeedbackRepository.class);
        assetEventRepository = Mockito.mock(AssetEventRepository.class);
        assetReviewRepository = Mockito.mock(AssetReviewRepository.class);
        assetSearchIndexer = Mockito.mock(AssetSearchIndexer.class);
        AssetSearchRankingService assetSearchRankingService = Mockito.mock(AssetSearchRankingService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<EmbeddingProvider> embeddingProvider = Mockito.mock(ObjectProvider.class);
        service = new AssetLifecycleService(assetRepository, assetRevisionRepository, assetSearchRepository,
                tagRepository, userRepository, pocRepository, assetFeedbackRepository, assetEventRepository,
                assetReviewRepository, assetSearchIndexer, assetSearchRankingService, embeddingProvider);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsNonHttpSourceUrlsBeforeWritingAnAsset() {
        CreateAssetRequest request = new CreateAssetRequest(CreateAssetRequest.AssetTypeEnum.POC,
                "owner", "Title", "Summary", "file:///C:/secret.txt");

        assertThatThrownBy(() -> service.createAsset(request, "author"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("absolute HTTP(S)");
    }

    @Test
    void createAssetMakesTheCallerSubmitterAndInitialRevisionAuthor() {
        CreateAssetRequest request = new CreateAssetRequest(CreateAssetRequest.AssetTypeEnum.POC,
                "owner-1", "Title", "Summary", "https://example.com/doc");
        when(userRepository.findById("owner-1")).thenReturn(Optional.of(activeInternalUser("owner-1")));
        stubSavesToReturnTheirArgument();

        AssetEditorResponse response = service.createAsset(request, "author-1");

        ArgumentCaptor<Asset> savedAsset = ArgumentCaptor.forClass(Asset.class);
        verify(assetRepository).save(savedAsset.capture());
        assertThat(savedAsset.getValue().getSubmittedByUserId()).isEqualTo("author-1");
        assertThat(savedAsset.getValue().getOwnerUserId()).isEqualTo("owner-1");
        ArgumentCaptor<AssetRevision> savedRevision = ArgumentCaptor.forClass(AssetRevision.class);
        verify(assetRevisionRepository).save(savedRevision.capture());
        assertThat(savedRevision.getValue().getAuthoredByUserId()).isEqualTo("author-1");
        assertThat(savedRevision.getValue().getRevisionNumber()).isEqualTo(1);
        assertThat(savedRevision.getValue().getState()).isEqualTo(AssetRevision.DRAFT);
        assertThat(response).isNotNull();
    }

    @Test
    void getAssetReturnsDetailForADiscoverableApprovedAsset() {
        Asset asset = approvedAsset();
        when(assetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));
        when(assetRevisionRepository.findById(asset.getApprovedRevisionId())).thenReturn(Optional.of(approvedRevision(asset)));

        AssetDetailResponse response = service.getAsset(asset.getId());

        assertThat(response).isNotNull();
    }

    @Test
    void getAssetThrowsNotFoundForAnArchivedAsset() {
        Asset asset = approvedAsset();
        asset.setArchivedAt(Instant.now());
        when(assetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));

        assertThatThrownBy(() -> service.getAsset(asset.getId())).isInstanceOf(AssetNotFoundException.class);
    }

    @Test
    void getAssetThrowsNotFoundWithNoApprovedRevisionYet() {
        Asset asset = approvedAsset();
        asset.setApprovedRevisionId(null);
        when(assetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));

        assertThatThrownBy(() -> service.getAsset(asset.getId())).isInstanceOf(AssetNotFoundException.class);
    }

    @Test
    void getWorkingRevisionIsVisibleToTheSubmitterOrOwner() {
        Asset asset = assetWithWorkingRevision("submitter-1", "owner-1");
        when(assetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));
        when(assetRevisionRepository.findById(asset.getWorkingRevisionId())).thenReturn(Optional.of(draftRevision(asset)));

        AssetEditorResponse response = service.getWorkingRevision(asset.getId(), "owner-1");

        assertThat(response).isNotNull();
    }

    @Test
    void getWorkingRevisionDeniesAnUnrelatedNonReviewerCaller() {
        Asset asset = assetWithWorkingRevision("submitter-1", "owner-1");
        when(assetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));

        assertThatThrownBy(() -> service.getWorkingRevision(asset.getId(), "someone-else"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void getWorkingRevisionIsVisibleToAnAssetReviewerWhoIsNotSubmitterOrOwner() {
        Asset asset = assetWithWorkingRevision("submitter-1", "owner-1");
        when(assetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));
        when(assetRevisionRepository.findById(asset.getWorkingRevisionId())).thenReturn(Optional.of(draftRevision(asset)));
        authenticateWithAuthority("ROLE_ASSET_REVIEWER");

        AssetEditorResponse response = service.getWorkingRevision(asset.getId(), "reviewer-1");

        assertThat(response).isNotNull();
    }

    @Test
    void createWorkingRevisionClonesTheApprovedRevisionIntoANewDraft() {
        Asset asset = approvedAsset();
        asset.setSubmittedByUserId("submitter-1");
        asset.setWorkingRevisionId(null);
        AssetRevision approved = approvedRevision(asset);
        approved.setRevisionNumber(1);
        when(assetRepository.lockById(asset.getId())).thenReturn(Optional.of(asset));
        when(assetRevisionRepository.findById(asset.getApprovedRevisionId())).thenReturn(Optional.of(approved));
        when(assetRevisionRepository.findByAssetIdOrderByRevisionNumberDesc(asset.getId())).thenReturn(List.of(approved));
        stubSavesToReturnTheirArgument();

        AssetEditorResponse response = service.createWorkingRevision(asset.getId(), "submitter-1");

        ArgumentCaptor<AssetRevision> draft = ArgumentCaptor.forClass(AssetRevision.class);
        verify(assetRevisionRepository).save(draft.capture());
        assertThat(draft.getValue().getRevisionNumber()).isEqualTo(2);
        assertThat(draft.getValue().getState()).isEqualTo(AssetRevision.DRAFT);
        assertThat(draft.getValue().getAuthoredByUserId()).isEqualTo("submitter-1");
        assertThat(response).isNotNull();
    }

    @Test
    void createWorkingRevisionConflictsWhenAPendingWorkingRevisionAlreadyExists() {
        Asset asset = assetWithWorkingRevision("submitter-1", "owner-1");
        AssetRevision pending = draftRevision(asset);
        pending.setState(AssetRevision.PENDING_REVIEW);
        when(assetRepository.lockById(asset.getId())).thenReturn(Optional.of(asset));
        when(assetRevisionRepository.findById(asset.getWorkingRevisionId())).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.createWorkingRevision(asset.getId(), "submitter-1"))
                .isInstanceOf(WorkingRevisionExistsException.class);
    }

    @Test
    void createWorkingRevisionAllowsCloningFromAChangesRequestedRevision() {
        Asset asset = assetWithWorkingRevision("submitter-1", "owner-1");
        AssetRevision changesRequested = draftRevision(asset);
        changesRequested.setState(AssetRevision.CHANGES_REQUESTED);
        changesRequested.setRevisionNumber(2);
        when(assetRepository.lockById(asset.getId())).thenReturn(Optional.of(asset));
        when(assetRevisionRepository.findById(asset.getWorkingRevisionId())).thenReturn(Optional.of(changesRequested));
        when(assetRevisionRepository.findByAssetIdOrderByRevisionNumberDesc(asset.getId())).thenReturn(List.of(changesRequested));
        stubSavesToReturnTheirArgument();

        AssetEditorResponse response = service.createWorkingRevision(asset.getId(), "submitter-1");

        assertThat(response).isNotNull();
        verify(assetRevisionRepository).save(any());
    }

    @Test
    void updateWorkingRevisionRejectsAStaleAssetVersion() {
        Asset asset = assetWithWorkingRevision("submitter-1", "owner-1");
        asset.setVersion(5L);
        when(assetRepository.lockById(asset.getId())).thenReturn(Optional.of(asset));

        assertThatThrownBy(() -> service.updateWorkingRevision(asset.getId(), "submitter-1",
                updateRequest(4L, 1L)))
                .isInstanceOf(AssetRevisionStaleException.class);
    }

    @Test
    void updateWorkingRevisionRejectsAStaleRevisionVersion() {
        Asset asset = assetWithWorkingRevision("submitter-1", "owner-1");
        asset.setVersion(5L);
        AssetRevision draft = draftRevision(asset);
        draft.setVersion(2L);
        when(assetRepository.lockById(asset.getId())).thenReturn(Optional.of(asset));
        when(assetRevisionRepository.findById(asset.getWorkingRevisionId())).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.updateWorkingRevision(asset.getId(), "submitter-1",
                updateRequest(5L, 1L)))
                .isInstanceOf(AssetRevisionStaleException.class);
    }

    @Test
    void updateWorkingRevisionRejectsEditingANonDraftRevision() {
        Asset asset = assetWithWorkingRevision("submitter-1", "owner-1");
        asset.setVersion(5L);
        AssetRevision pending = draftRevision(asset);
        pending.setState(AssetRevision.PENDING_REVIEW);
        pending.setVersion(1L);
        when(assetRepository.lockById(asset.getId())).thenReturn(Optional.of(asset));
        when(assetRevisionRepository.findById(asset.getWorkingRevisionId())).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.updateWorkingRevision(asset.getId(), "submitter-1",
                updateRequest(5L, 1L)))
                .isInstanceOf(InvalidAssetTransitionException.class);
    }

    @Test
    void updateWorkingRevisionAppliesFieldsOnAValidDraftEdit() {
        Asset asset = assetWithWorkingRevision("submitter-1", "owner-1");
        asset.setVersion(5L);
        AssetRevision draft = draftRevision(asset);
        draft.setVersion(1L);
        when(assetRepository.lockById(asset.getId())).thenReturn(Optional.of(asset));
        when(assetRevisionRepository.findById(asset.getWorkingRevisionId())).thenReturn(Optional.of(draft));

        service.updateWorkingRevision(asset.getId(), "submitter-1", updateRequest(5L, 1L));

        assertThat(draft.getTitle()).isEqualTo("Updated title");
        assertThat(draft.getSummary()).isEqualTo("Updated summary");
    }

    @Test
    void submitWorkingRevisionTransitionsDraftToPendingReviewAndStampsSubmittedAt() {
        Asset asset = assetWithWorkingRevision("submitter-1", "owner-1");
        AssetRevision draft = draftRevision(asset);
        draft.setVersion(1L);
        when(assetRepository.lockById(asset.getId())).thenReturn(Optional.of(asset));
        when(assetRevisionRepository.findById(asset.getWorkingRevisionId())).thenReturn(Optional.of(draft));

        service.submitWorkingRevision(asset.getId(), "submitter-1", 1L);

        assertThat(draft.getState()).isEqualTo(AssetRevision.PENDING_REVIEW);
        assertThat(draft.getSubmittedAt()).isNotNull();
    }

    @Test
    void submitWorkingRevisionRejectsANonDraftRevision() {
        Asset asset = assetWithWorkingRevision("submitter-1", "owner-1");
        AssetRevision pending = draftRevision(asset);
        pending.setState(AssetRevision.PENDING_REVIEW);
        pending.setVersion(1L);
        when(assetRepository.lockById(asset.getId())).thenReturn(Optional.of(asset));
        when(assetRevisionRepository.findById(asset.getWorkingRevisionId())).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.submitWorkingRevision(asset.getId(), "submitter-1", 1L))
                .isInstanceOf(InvalidAssetTransitionException.class);
    }

    @Test
    void archiveAssetSetsArchivedFieldsAndRemovesTheSearchDocument() {
        Asset asset = assetWithWorkingRevision("submitter-1", "owner-1");
        asset.setVersion(3L);
        when(assetRepository.lockById(asset.getId())).thenReturn(Optional.of(asset));

        service.archiveAsset(asset.getId(), "submitter-1", 3L);

        assertThat(asset.getArchivedAt()).isNotNull();
        assertThat(asset.getArchivedByUserId()).isEqualTo("submitter-1");
        verify(assetSearchIndexer).remove(asset.getId());
    }

    @Test
    void archiveAssetDeniesACallerWhoIsNeitherSubmitterOwnerNorAdmin() {
        Asset asset = assetWithWorkingRevision("submitter-1", "owner-1");
        asset.setVersion(3L);
        when(assetRepository.lockById(asset.getId())).thenReturn(Optional.of(asset));

        assertThatThrownBy(() -> service.archiveAsset(asset.getId(), "someone-else", 3L))
                .isInstanceOf(ApiException.class);
        verify(assetSearchIndexer, never()).remove(any());
    }

    @Test
    void archiveAssetRejectsAStaleExpectedVersion() {
        Asset asset = assetWithWorkingRevision("submitter-1", "owner-1");
        asset.setVersion(3L);
        when(assetRepository.lockById(asset.getId())).thenReturn(Optional.of(asset));

        assertThatThrownBy(() -> service.archiveAsset(asset.getId(), "submitter-1", 2L))
                .isInstanceOf(AssetRevisionStaleException.class);
    }

    @Test
    void listMyAssetsReturnsOwnedAssetsNewestFirst() {
        Asset older = approvedAsset();
        older.setOwnerUserId("owner-1");
        older.setUpdatedAt(Instant.now().minusSeconds(120));
        Asset newer = approvedAsset();
        newer.setOwnerUserId("owner-1");
        newer.setUpdatedAt(Instant.now());
        when(assetRepository.findByOwnerUserIdAndArchivedAtIsNull("owner-1")).thenReturn(List.of(older, newer));
        when(assetRevisionRepository.findById(older.getApprovedRevisionId())).thenReturn(Optional.of(approvedRevision(older)));
        when(assetRevisionRepository.findById(newer.getApprovedRevisionId())).thenReturn(Optional.of(approvedRevision(newer)));

        AssetMinePageResponse response = service.listMyAssets("owner-1", 0, 20);

        assertThat(response.getContent()).extracting(item -> item.getId())
                .containsExactly(newer.getId(), older.getId());
    }

    @Test
    void putFeedbackCreatesFeedbackForADiscoverableAsset() {
        Asset asset = approvedAsset();
        when(assetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));
        when(assetFeedbackRepository.findByAssetIdAndUserId(asset.getId(), "employee-1")).thenReturn(Optional.empty());

        AssetFeedbackResponse response = service.putFeedback(asset.getId(), "employee-1",
                new AssetFeedbackRequest(AssetFeedbackRequest.RatingEnum.HELPFUL));

        ArgumentCaptor<AssetFeedback> saved = ArgumentCaptor.forClass(AssetFeedback.class);
        verify(assetFeedbackRepository).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo("employee-1");
        assertThat(saved.getValue().getRating()).isEqualTo("HELPFUL");
        assertThat(response).isNotNull();
    }

    @Test
    void putFeedbackThrowsNotFoundForAnUndiscoverableAsset() {
        Asset asset = approvedAsset();
        asset.setArchivedAt(Instant.now());
        when(assetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));

        assertThatThrownBy(() -> service.putFeedback(asset.getId(), "employee-1",
                new AssetFeedbackRequest(AssetFeedbackRequest.RatingEnum.HELPFUL)))
                .isInstanceOf(AssetNotFoundException.class);
    }

    @Test
    void recordEventSavesTheEventWithItsSearchSessionId() {
        Asset asset = approvedAsset();
        when(assetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));
        UUID sessionId = UUID.randomUUID();
        AssetEventRequest request = new AssetEventRequest(AssetEventRequest.EventTypeEnum.SOURCE_OPEN)
                .searchSessionId(sessionId);

        service.recordEvent(asset.getId(), "employee-1", request);

        ArgumentCaptor<AssetEvent> saved = ArgumentCaptor.forClass(AssetEvent.class);
        verify(assetEventRepository).save(saved.capture());
        assertThat(saved.getValue().getEventType()).isEqualTo("SOURCE_OPEN");
        assertThat(saved.getValue().getSearchSessionId()).isEqualTo(sessionId);
        assertThat(saved.getValue().getAssetId()).isEqualTo(asset.getId());
    }

    @Test
    void recordsAPrivacyMinimizedSearchEventForKeywordSearches() {
        when(assetSearchRepository.findRankedAssetIds(eq("onboarding"), any(), any(), any(), eq(false),
                eq(20), eq(0))).thenReturn(List.of());
        when(assetSearchRepository.countRankedAssets(eq("onboarding"), any(), any(), any(), eq(false)))
                .thenReturn(0L);

        AssetPageResponse response = service.listAssets("  onboarding  ", null, null, null,
                false, 0, 20, "employee-1");

        ArgumentCaptor<AssetEvent> event = ArgumentCaptor.forClass(AssetEvent.class);
        verify(assetEventRepository).save(event.capture());
        assertThat(event.getValue().getEventType()).isEqualTo("SEARCH");
        assertThat(event.getValue().getUserId()).isEqualTo("employee-1");
        assertThat(event.getValue().getAssetId()).isNull();
        assertThat(event.getValue().getSearchSessionId()).isEqualTo(response.getSearchSessionId());
    }

    // -- fixtures --------------------------------------------------------------------------------

    private void stubSavesToReturnTheirArgument() {
        when(assetRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(assetRevisionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static User activeInternalUser(String id) {
        User user = new User();
        user.setId(id);
        user.setEmail(id + "@sailssoftware.com");
        user.setFirstName("Test");
        user.setLastName("User");
        user.setAccountType(AccountType.INTERNAL);
        user.setStatus(UserStatus.ACTIVE);
        return user;
    }

    private static Asset approvedAsset() {
        Asset asset = new Asset();
        asset.setId(UUID.randomUUID());
        asset.setAssetType("POC");
        asset.setOwnerUserId("owner-1");
        asset.setSubmittedByUserId("submitter-1");
        asset.setApprovedRevisionId(UUID.randomUUID());
        asset.setVersion(1L);
        asset.setUpdatedAt(Instant.now());
        return asset;
    }

    private static Asset assetWithWorkingRevision(String submitterId, String ownerId) {
        Asset asset = new Asset();
        asset.setId(UUID.randomUUID());
        asset.setAssetType("POC");
        asset.setSubmittedByUserId(submitterId);
        asset.setOwnerUserId(ownerId);
        asset.setWorkingRevisionId(UUID.randomUUID());
        asset.setVersion(1L);
        return asset;
    }

    private static AssetRevision approvedRevision(Asset asset) {
        AssetRevision revision = new AssetRevision();
        revision.setId(asset.getApprovedRevisionId());
        revision.setAssetId(asset.getId());
        revision.setState(AssetRevision.APPROVED);
        revision.setTitle("Approved title");
        revision.setSummary("Approved summary");
        revision.setSourceUrl("https://example.com/doc");
        revision.setTags(new java.util.HashSet<>());
        return revision;
    }

    private static AssetRevision draftRevision(Asset asset) {
        AssetRevision revision = new AssetRevision();
        revision.setId(asset.getWorkingRevisionId());
        revision.setAssetId(asset.getId());
        revision.setState(AssetRevision.DRAFT);
        revision.setTitle("Draft title");
        revision.setSummary("Draft summary");
        revision.setSourceUrl("https://example.com/doc");
        revision.setTags(new java.util.HashSet<>());
        return revision;
    }

    private static UpdateAssetRevisionRequest updateRequest(Long assetExpectedVersion, Long revisionExpectedVersion) {
        return new UpdateAssetRevisionRequest("Updated title", "Updated summary", "https://example.com/updated",
                revisionExpectedVersion, assetExpectedVersion);
    }

    private static void authenticateWithAuthority(String authority) {
        Jwt jwt = new Jwt("token", Instant.now(), Instant.now().plusSeconds(300),
                java.util.Map.of("alg", "RS256"), java.util.Map.of("sub", "reviewer-1"));
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority(authority))));
    }
}
