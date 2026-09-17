package com.sails.ai.selfserviceapi.asset.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sails.ai.selfserviceapi.asset.ai.AssetAiProvider;
import com.sails.ai.selfserviceapi.asset.ai.AssetAiProviderException;
import com.sails.ai.selfserviceapi.asset.ai.AssetAiSuggestionResult;
import com.sails.ai.selfserviceapi.asset.entity.Asset;
import com.sails.ai.selfserviceapi.asset.entity.AssetAiSuggestion;
import com.sails.ai.selfserviceapi.asset.entity.AssetRevision;
import com.sails.ai.selfserviceapi.asset.exception.AssetAiUnavailableException;
import com.sails.ai.selfserviceapi.asset.repository.AssetAiSuggestionRepository;
import com.sails.ai.selfserviceapi.asset.repository.AssetRepository;
import com.sails.ai.selfserviceapi.asset.repository.AssetRevisionRepository;
import com.sails.ai.selfserviceapi.common.exception.ApiException;
import com.sails.ai.selfserviceapi.generated.model.AssetAiSuggestionResponse;
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
import tools.jackson.databind.ObjectMapper;

class AssetAiSuggestionServiceTest {

    private AssetRepository assetRepository;
    private AssetRevisionRepository assetRevisionRepository;
    private AssetAiSuggestionRepository assetAiSuggestionRepository;
    @SuppressWarnings("unchecked")
    private final ObjectProvider<AssetAiProvider> providerObjectProvider = Mockito.mock(ObjectProvider.class);
    private AssetAiProvider provider;
    private AssetAiSuggestionService service;

    @BeforeEach
    void setUp() {
        assetRepository = Mockito.mock(AssetRepository.class);
        assetRevisionRepository = Mockito.mock(AssetRevisionRepository.class);
        assetAiSuggestionRepository = Mockito.mock(AssetAiSuggestionRepository.class);
        provider = Mockito.mock(AssetAiProvider.class);
        when(provider.providerName()).thenReturn("gemini");
        when(provider.modelName()).thenReturn("gemini-2.5-flash");
        service = new AssetAiSuggestionService(assetRepository, assetRevisionRepository,
                assetAiSuggestionRepository, providerObjectProvider, new ObjectMapper());
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void startSuggestionsIsUnavailableWithNoProviderBean() {
        when(providerObjectProvider.getIfAvailable()).thenReturn(null);
        Asset asset = assetWithWorkingRevision("submitter-1", "owner-1");

        assertThatThrownBy(() -> service.startSuggestions(asset.getId(), "submitter-1"))
                .isInstanceOf(AssetAiUnavailableException.class);
    }

    @Test
    void startSuggestionsDeniesACallerWhoIsNeitherSubmitterNorOwner() {
        when(providerObjectProvider.getIfAvailable()).thenReturn(provider);
        Asset asset = assetWithWorkingRevision("submitter-1", "owner-1");
        when(assetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));

        assertThatThrownBy(() -> service.startSuggestions(asset.getId(), "someone-else"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("submitter or owner");
    }

    @Test
    void reusesAPriorSucceededRunWithTheSameInputChecksum() {
        when(providerObjectProvider.getIfAvailable()).thenReturn(provider);
        Asset asset = assetWithWorkingRevision("submitter-1", "owner-1");
        AssetRevision working = workingRevision(asset);
        when(assetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));
        when(assetRevisionRepository.findById(working.getId())).thenReturn(Optional.of(working));
        AssetAiSuggestion priorRun = new AssetAiSuggestion();
        priorRun.setId(UUID.randomUUID());
        priorRun.setRevisionId(working.getId());
        priorRun.setStatus("SUCCEEDED");
        when(assetAiSuggestionRepository
                .findFirstByRevisionIdAndInputChecksumAndProviderAndModelAndSchemaVersionAndStatusOrderByCreatedAtDesc(
                        any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(priorRun));

        service.startSuggestions(asset.getId(), "submitter-1");

        verify(provider, never()).suggest(any());
        verify(assetAiSuggestionRepository, never()).saveAndFlush(any());
    }

    @Test
    void runsANewSuggestionAndStoresTheSucceededResultWhenNoPriorRunMatches() {
        when(providerObjectProvider.getIfAvailable()).thenReturn(provider);
        Asset asset = assetWithWorkingRevision("submitter-1", "owner-1");
        AssetRevision working = workingRevision(asset);
        when(assetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));
        when(assetRevisionRepository.findById(working.getId())).thenReturn(Optional.of(working));
        when(assetAiSuggestionRepository
                .findFirstByRevisionIdAndInputChecksumAndProviderAndModelAndSchemaVersionAndStatusOrderByCreatedAtDesc(
                        any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(provider.suggest(any())).thenReturn(
                new AssetAiSuggestionResult("Better Title", "Better summary", List.of("Tag One", "tag one", "  ", "Tag Two")));

        AssetAiSuggestionResponse response = service.startSuggestions(asset.getId(), "submitter-1");

        // saveAndFlush's argument is the same mutable entity runSuggestion keeps updating
        // afterward, so by now it reflects the final state (SUCCEEDED) rather than the "RUNNING"
        // value it held at the moment of that call — assert the call happened and the fields that
        // don't change after it, not a status snapshot in time.
        ArgumentCaptor<AssetAiSuggestion> saved = ArgumentCaptor.forClass(AssetAiSuggestion.class);
        verify(assetAiSuggestionRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getProvider()).isEqualTo("gemini");
        assertThat(response.getStatus().getValue()).isEqualTo("SUCCEEDED");
        assertThat(response.getSuggestedTitle()).isEqualTo("Better Title");
        // Blank entries are dropped and each value is trimmed, but dedup is case-sensitive
        // (normalizeSuggestedTags uses plain .distinct(), not a normalized-name comparison).
        assertThat(response.getSuggestedTags()).containsExactly("Tag One", "tag one", "Tag Two");
    }

    @Test
    void aProviderFailureMarksTheRunFailedWithItsErrorCodeAndDoesNotPropagate() {
        when(providerObjectProvider.getIfAvailable()).thenReturn(provider);
        Asset asset = assetWithWorkingRevision("submitter-1", "owner-1");
        AssetRevision working = workingRevision(asset);
        when(assetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));
        when(assetRevisionRepository.findById(working.getId())).thenReturn(Optional.of(working));
        when(assetAiSuggestionRepository
                .findFirstByRevisionIdAndInputChecksumAndProviderAndModelAndSchemaVersionAndStatusOrderByCreatedAtDesc(
                        any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(provider.suggest(any())).thenThrow(new AssetAiProviderException("PROVIDER_TIMEOUT", "timed out"));

        AssetAiSuggestionResponse response = service.startSuggestions(asset.getId(), "submitter-1");

        assertThat(response.getStatus().getValue()).isEqualTo("FAILED");
        assertThat(response.getErrorCode()).isEqualTo("PROVIDER_TIMEOUT");
    }

    @Test
    void anUnexpectedRuntimeExceptionAlsoMarksTheRunFailedRatherThanPropagating() {
        when(providerObjectProvider.getIfAvailable()).thenReturn(provider);
        Asset asset = assetWithWorkingRevision("submitter-1", "owner-1");
        AssetRevision working = workingRevision(asset);
        when(assetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));
        when(assetRevisionRepository.findById(working.getId())).thenReturn(Optional.of(working));
        when(assetAiSuggestionRepository
                .findFirstByRevisionIdAndInputChecksumAndProviderAndModelAndSchemaVersionAndStatusOrderByCreatedAtDesc(
                        any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(provider.suggest(any())).thenThrow(new IllegalStateException("boom"));

        AssetAiSuggestionResponse response = service.startSuggestions(asset.getId(), "submitter-1");

        assertThat(response.getStatus().getValue()).isEqualTo("FAILED");
        assertThat(response.getErrorCode()).isEqualTo("PROVIDER_ERROR");
    }

    @Test
    void getLatestSuggestionIsVisibleToAReviewerWhoIsNeitherSubmitterNorOwner() {
        Asset asset = assetWithWorkingRevision("submitter-1", "owner-1");
        AssetRevision working = workingRevision(asset);
        when(assetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));
        when(assetRevisionRepository.findById(working.getId())).thenReturn(Optional.of(working));
        AssetAiSuggestion run = new AssetAiSuggestion();
        run.setStatus("SUCCEEDED");
        when(assetAiSuggestionRepository.findFirstByRevisionIdOrderByCreatedAtDesc(working.getId()))
                .thenReturn(Optional.of(run));
        authenticateAsReviewer();

        AssetAiSuggestionResponse response = service.getLatestSuggestion(asset.getId(), "reviewer-1");

        assertThat(response.getStatus().getValue()).isEqualTo("SUCCEEDED");
    }

    @Test
    void getLatestSuggestionDeniesAnUnrelatedNonReviewerCaller() {
        Asset asset = assetWithWorkingRevision("submitter-1", "owner-1");
        when(assetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));
        when(assetRevisionRepository.findById(asset.getWorkingRevisionId()))
                .thenReturn(Optional.of(workingRevision(asset)));

        assertThatThrownBy(() -> service.getLatestSuggestion(asset.getId(), "someone-else"))
                .isInstanceOf(ApiException.class);
    }

    private static Asset assetWithWorkingRevision(String submitterId, String ownerId) {
        Asset asset = new Asset();
        asset.setId(UUID.randomUUID());
        asset.setSubmittedByUserId(submitterId);
        asset.setOwnerUserId(ownerId);
        asset.setAssetType("DOCUMENT");
        asset.setWorkingRevisionId(UUID.randomUUID());
        return asset;
    }

    private static AssetRevision workingRevision(Asset asset) {
        AssetRevision revision = new AssetRevision();
        revision.setId(asset.getWorkingRevisionId());
        revision.setAssetId(asset.getId());
        revision.setTitle("Draft title");
        revision.setSummary("Draft summary");
        revision.setTags(new java.util.HashSet<>());
        return revision;
    }

    private static void authenticateAsReviewer() {
        Jwt jwt = new Jwt("token", java.time.Instant.now(), java.time.Instant.now().plusSeconds(300),
                java.util.Map.of("alg", "RS256"), java.util.Map.of("sub", "reviewer-1"));
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_ASSET_REVIEWER"))));
    }
}
