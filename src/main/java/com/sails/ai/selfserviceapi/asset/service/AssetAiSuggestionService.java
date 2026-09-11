package com.sails.ai.selfserviceapi.asset.service;

import com.sails.ai.selfserviceapi.asset.ai.AssetAiProvider;
import com.sails.ai.selfserviceapi.asset.ai.AssetAiProviderException;
import com.sails.ai.selfserviceapi.asset.ai.AssetAiSuggestionInput;
import com.sails.ai.selfserviceapi.asset.ai.AssetAiSuggestionResult;
import com.sails.ai.selfserviceapi.asset.entity.Asset;
import com.sails.ai.selfserviceapi.asset.entity.AssetAiSuggestion;
import com.sails.ai.selfserviceapi.asset.entity.AssetRevision;
import com.sails.ai.selfserviceapi.asset.entity.Tag;
import com.sails.ai.selfserviceapi.asset.exception.AssetAiUnavailableException;
import com.sails.ai.selfserviceapi.asset.exception.AssetNotFoundException;
import com.sails.ai.selfserviceapi.asset.repository.AssetAiSuggestionRepository;
import com.sails.ai.selfserviceapi.asset.repository.AssetRepository;
import com.sails.ai.selfserviceapi.asset.repository.AssetRevisionRepository;
import com.sails.ai.selfserviceapi.common.exception.ApiException;
import com.sails.ai.selfserviceapi.generated.model.AssetAiSuggestionResponse;
import com.sails.ai.selfserviceapi.security.CurrentUser;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Orchestrates one metadata-suggestion run: builds the bounded canonical input, dedupes by input
 * checksum, calls the configured {@link AssetAiProvider}, and persists the result. See
 * docs/specs/asset-hub.md's AI Suggestion Contract. The provider bean only exists when
 * asset-hub.ai-enabled=true (AssetAiClientConfig), so a missing bean here IS the disabled path —
 * no separate flag check needed.
 */
@Service
public class AssetAiSuggestionService {

    private static final String SCHEMA_VERSION = "1";
    private static final int MAX_SUGGESTED_TAGS = 10;
    private static final int MAX_TAG_LENGTH = 80;
    private static final int MAX_TITLE_LENGTH = 200;

    private final AssetRepository assetRepository;
    private final AssetRevisionRepository assetRevisionRepository;
    private final AssetAiSuggestionRepository assetAiSuggestionRepository;
    private final ObjectProvider<AssetAiProvider> assetAiProvider;
    private final ObjectMapper objectMapper;

    public AssetAiSuggestionService(AssetRepository assetRepository, AssetRevisionRepository assetRevisionRepository,
                                     AssetAiSuggestionRepository assetAiSuggestionRepository,
                                     ObjectProvider<AssetAiProvider> assetAiProvider, ObjectMapper objectMapper) {
        this.assetRepository = assetRepository;
        this.assetRevisionRepository = assetRevisionRepository;
        this.assetAiSuggestionRepository = assetAiSuggestionRepository;
        this.assetAiProvider = assetAiProvider;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public AssetAiSuggestionResponse startSuggestions(UUID assetId, String callerId) {
        AssetAiProvider provider = assetAiProvider.getIfAvailable();
        if (provider == null) {
            throw new AssetAiUnavailableException();
        }

        Asset asset = assetRepository.findById(assetId).orElseThrow(() -> new AssetNotFoundException(assetId));
        requireSubmitterOrOwner(asset, callerId);
        AssetRevision working = requireWorkingRevision(asset);

        AssetAiSuggestionInput input = buildInput(asset, working);
        String checksum = checksum(input);

        return assetAiSuggestionRepository
                .findFirstByRevisionIdAndInputChecksumAndProviderAndModelAndSchemaVersionAndStatusOrderByCreatedAtDesc(
                        working.getId(), checksum, provider.providerName(), provider.modelName(), SCHEMA_VERSION, "SUCCEEDED")
                .map(this::toResponse)
                .orElseGet(() -> runSuggestion(working, provider, input, checksum));
    }

    @Transactional(readOnly = true)
    public AssetAiSuggestionResponse getLatestSuggestion(UUID assetId, String callerId) {
        Asset asset = assetRepository.findById(assetId).orElseThrow(() -> new AssetNotFoundException(assetId));
        requireSubmitterOwnerOrReviewer(asset, callerId);
        AssetRevision working = requireWorkingRevision(asset);

        return assetAiSuggestionRepository.findFirstByRevisionIdOrderByCreatedAtDesc(working.getId())
                .map(this::toResponse)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ASSET_NOT_FOUND",
                        "No AI suggestion run exists yet for this revision."));
    }

    // -- helpers -------------------------------------------------------------------------------

    private AssetAiSuggestionResponse runSuggestion(AssetRevision working, AssetAiProvider provider,
                                                      AssetAiSuggestionInput input, String checksum) {
        AssetAiSuggestion run = new AssetAiSuggestion();
        run.setRevisionId(working.getId());
        run.setProvider(provider.providerName());
        run.setModel(provider.modelName());
        run.setSchemaVersion(SCHEMA_VERSION);
        run.setInputChecksum(checksum);
        run.setStatus("RUNNING");
        assetAiSuggestionRepository.saveAndFlush(run);

        try {
            AssetAiSuggestionResult result = provider.suggest(input);
            run.setStatus("SUCCEEDED");
            run.setSuggestedTitle(truncate(result.suggestedTitle(), MAX_TITLE_LENGTH));
            run.setSuggestedSummary(result.suggestedSummary());
            run.setSuggestedTags(writeTags(normalizeSuggestedTags(result.suggestedTags())));
        } catch (AssetAiProviderException e) {
            run.setStatus("FAILED");
            run.setErrorCode(e.errorCode());
        } catch (RuntimeException e) {
            run.setStatus("FAILED");
            run.setErrorCode("PROVIDER_ERROR");
        }
        return toResponse(run);
    }

    private AssetAiSuggestionInput buildInput(Asset asset, AssetRevision working) {
        List<String> normalizedTags = working.getTags().stream()
                .map(Tag::getNormalizedName)
                .sorted()
                .toList();
        return new AssetAiSuggestionInput(asset.getAssetType(), working.getTitle(), working.getSummary(),
                working.getProblemStatement(), working.getBusinessImpact(), working.getSolutionOverview(), normalizedTags);
    }

    private String checksum(AssetAiSuggestionInput input) {
        try {
            byte[] canonicalJson = objectMapper.writeValueAsBytes(input);
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonicalJson);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is a required JDK algorithm", e);
        }
    }

    private List<String> normalizeSuggestedTags(List<String> tags) {
        if (tags == null) {
            return List.of();
        }
        return tags.stream()
                .filter(t -> t != null && !t.isBlank())
                .map(t -> truncate(t.trim(), MAX_TAG_LENGTH))
                .distinct()
                .limit(MAX_SUGGESTED_TAGS)
                .toList();
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private String writeTags(List<String> tags) {
        return objectMapper.writeValueAsString(tags);
    }

    private List<String> readTags(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        return objectMapper.readValue(json, new TypeReference<List<String>>() {
        });
    }

    private AssetAiSuggestionResponse toResponse(AssetAiSuggestion run) {
        return AssetResponseMapper.toAiSuggestionResponse(run, readTags(run.getSuggestedTags()));
    }

    private AssetRevision requireWorkingRevision(Asset asset) {
        if (asset.getWorkingRevisionId() == null) {
            throw new AssetNotFoundException(asset.getId());
        }
        return assetRevisionRepository.findById(asset.getWorkingRevisionId())
                .orElseThrow(() -> new AssetNotFoundException(asset.getId()));
    }

    private boolean isSubmitterOrOwner(Asset asset, String callerId) {
        return asset.getSubmittedByUserId().equals(callerId) || asset.getOwnerUserId().equals(callerId);
    }

    private void requireSubmitterOrOwner(Asset asset, String callerId) {
        if (!isSubmitterOrOwner(asset, callerId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Only the submitter or owner can perform this action.");
        }
    }

    private void requireSubmitterOwnerOrReviewer(Asset asset, String callerId) {
        if (!isSubmitterOrOwner(asset, callerId) && !CurrentUser.hasRole("ASSET_REVIEWER")) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Only the submitter, owner, or a reviewer can view this.");
        }
    }
}
