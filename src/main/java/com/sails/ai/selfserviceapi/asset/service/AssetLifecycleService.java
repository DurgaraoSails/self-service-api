package com.sails.ai.selfserviceapi.asset.service;

import com.sails.ai.selfserviceapi.asset.entity.Asset;
import com.sails.ai.selfserviceapi.asset.entity.AssetEvent;
import com.sails.ai.selfserviceapi.asset.entity.AssetFeedback;
import com.sails.ai.selfserviceapi.asset.entity.AssetRevision;
import com.sails.ai.selfserviceapi.asset.entity.Tag;
import com.sails.ai.selfserviceapi.asset.exception.AssetNotFoundException;
import com.sails.ai.selfserviceapi.asset.exception.AssetRevisionStaleException;
import com.sails.ai.selfserviceapi.asset.exception.InvalidAssetTransitionException;
import com.sails.ai.selfserviceapi.asset.exception.InvalidPocAssociationException;
import com.sails.ai.selfserviceapi.asset.exception.WorkingRevisionExistsException;
import com.sails.ai.selfserviceapi.asset.repository.AssetEventRepository;
import com.sails.ai.selfserviceapi.asset.repository.AssetFeedbackRepository;
import com.sails.ai.selfserviceapi.asset.repository.AssetRepository;
import com.sails.ai.selfserviceapi.asset.repository.AssetReviewRepository;
import com.sails.ai.selfserviceapi.asset.repository.AssetRevisionRepository;
import com.sails.ai.selfserviceapi.asset.repository.AssetSearchRepository;
import com.sails.ai.selfserviceapi.asset.repository.TagRepository;
import com.sails.ai.selfserviceapi.asset.search.AssetSearchIndexer;
import com.sails.ai.selfserviceapi.common.exception.ApiException;
import com.sails.ai.selfserviceapi.generated.model.AssetDetailResponse;
import com.sails.ai.selfserviceapi.generated.model.AssetEditorResponse;
import com.sails.ai.selfserviceapi.generated.model.AssetEventRequest;
import com.sails.ai.selfserviceapi.generated.model.AssetFacetsResponse;
import com.sails.ai.selfserviceapi.generated.model.AssetFacetsResponseOwnersInner;
import com.sails.ai.selfserviceapi.generated.model.AssetFacetsResponseTagsInner;
import com.sails.ai.selfserviceapi.generated.model.AssetFacetsResponseTypesInner;
import com.sails.ai.selfserviceapi.generated.model.AssetFeedbackRequest;
import com.sails.ai.selfserviceapi.generated.model.AssetFeedbackResponse;
import com.sails.ai.selfserviceapi.generated.model.AssetMinePageResponse;
import com.sails.ai.selfserviceapi.generated.model.AssetMineSummaryResponse;
import com.sails.ai.selfserviceapi.generated.model.AssetPageResponse;
import com.sails.ai.selfserviceapi.generated.model.AssetSummaryResponse;
import com.sails.ai.selfserviceapi.generated.model.CreateAssetRequest;
import com.sails.ai.selfserviceapi.generated.model.UpdateAssetRevisionRequest;
import com.sails.ai.selfserviceapi.poc.entity.Poc;
import com.sails.ai.selfserviceapi.poc.repository.PocRepository;
import com.sails.ai.selfserviceapi.security.CurrentUser;
import com.sails.ai.selfserviceapi.user.entity.AccountType;
import com.sails.ai.selfserviceapi.user.entity.User;
import com.sails.ai.selfserviceapi.user.entity.UserStatus;
import com.sails.ai.selfserviceapi.user.repository.UserRepository;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AssetLifecycleService {

    private final AssetRepository assetRepository;
    private final AssetRevisionRepository assetRevisionRepository;
    private final AssetSearchRepository assetSearchRepository;
    private final TagRepository tagRepository;
    private final UserRepository userRepository;
    private final PocRepository pocRepository;
    private final AssetFeedbackRepository assetFeedbackRepository;
    private final AssetEventRepository assetEventRepository;
    private final AssetReviewRepository assetReviewRepository;
    private final AssetSearchIndexer assetSearchIndexer;

    public AssetLifecycleService(AssetRepository assetRepository, AssetRevisionRepository assetRevisionRepository,
                                  AssetSearchRepository assetSearchRepository, TagRepository tagRepository,
                                  UserRepository userRepository, PocRepository pocRepository,
                                  AssetFeedbackRepository assetFeedbackRepository, AssetEventRepository assetEventRepository,
                                  AssetReviewRepository assetReviewRepository, AssetSearchIndexer assetSearchIndexer) {
        this.assetRepository = assetRepository;
        this.assetRevisionRepository = assetRevisionRepository;
        this.assetSearchRepository = assetSearchRepository;
        this.tagRepository = tagRepository;
        this.userRepository = userRepository;
        this.pocRepository = pocRepository;
        this.assetFeedbackRepository = assetFeedbackRepository;
        this.assetEventRepository = assetEventRepository;
        this.assetReviewRepository = assetReviewRepository;
        this.assetSearchIndexer = assetSearchIndexer;
    }

    @Transactional
    public AssetEditorResponse createAsset(CreateAssetRequest request, String callerId) {
        validateSourceUrl(request.getSourceUrl());
        User owner = requireActiveInternalUser(request.getOwnerUserId());
        String assetType = request.getAssetType().getValue();
        validatePocAssociation(request.getPocId(), assetType);

        Asset asset = new Asset();
        asset.setAssetType(assetType);
        asset.setOwnerUserId(owner.getId());
        asset.setSubmittedByUserId(callerId);
        asset.setPocId(request.getPocId());
        assetRepository.save(asset);

        AssetRevision revision = new AssetRevision();
        revision.setAssetId(asset.getId());
        revision.setRevisionNumber(1);
        revision.setState(AssetRevision.DRAFT);
        revision.setTitle(request.getTitle());
        revision.setSummary(request.getSummary());
        revision.setProblemStatement(request.getProblemStatement());
        revision.setBusinessImpact(request.getBusinessImpact());
        revision.setSolutionOverview(request.getSolutionOverview());
        revision.setSourceUrl(request.getSourceUrl());
        revision.setAuthoredByUserId(callerId);
        revision.setTags(resolveTags(request.getTags()));
        assetRevisionRepository.save(revision);

        asset.setWorkingRevisionId(revision.getId());
        // Flush so the deferred UPDATE from setWorkingRevisionId above (and any @Version bump it
        // causes) is applied now — otherwise the version in this response is stale by the time the
        // transaction actually commits, and the client's next expectedVersion would be rejected.
        assetRepository.flush();

        return AssetResponseMapper.toEditorResponse(asset, revision, null);
    }

    @Transactional(readOnly = true)
    public AssetDetailResponse getAsset(UUID assetId) {
        Asset asset = requireDiscoverableAsset(assetId);
        AssetRevision approved = assetRevisionRepository.findById(asset.getApprovedRevisionId())
                .orElseThrow(() -> new AssetNotFoundException(assetId));
        String ownerDisplayName = userRepository.findById(asset.getOwnerUserId())
                .map(AssetResponseMapper::displayName).orElse(null);
        Poc poc = asset.getPocId() != null ? pocRepository.findById(asset.getPocId()).orElse(null) : null;
        return AssetResponseMapper.toDetailResponse(asset, approved, ownerDisplayName, poc);
    }

    @Transactional(readOnly = true)
    public AssetEditorResponse getWorkingRevision(UUID assetId, String callerId) {
        Asset asset = assetRepository.findById(assetId).orElseThrow(() -> new AssetNotFoundException(assetId));
        requireSubmitterOwnerOrReviewer(asset, callerId);
        if (asset.getWorkingRevisionId() == null) {
            throw new AssetNotFoundException(assetId);
        }
        AssetRevision working = assetRevisionRepository.findById(asset.getWorkingRevisionId())
                .orElseThrow(() -> new AssetNotFoundException(assetId));
        AssetRevision lastApproved = asset.getApprovedRevisionId() != null
                ? assetRevisionRepository.findById(asset.getApprovedRevisionId()).orElse(null) : null;
        return AssetResponseMapper.toEditorResponse(asset, working, lastApproved);
    }

    @Transactional
    public AssetEditorResponse createWorkingRevision(UUID assetId, String callerId) {
        Asset asset = assetRepository.lockById(assetId).orElseThrow(() -> new AssetNotFoundException(assetId));
        requireSubmitterOrOwner(asset, callerId);

        if (asset.getWorkingRevisionId() != null) {
            AssetRevision existing = assetRevisionRepository.findById(asset.getWorkingRevisionId())
                    .orElseThrow(() -> new AssetNotFoundException(assetId));
            boolean editable = AssetRevision.CHANGES_REQUESTED.equals(existing.getState())
                    || AssetRevision.REJECTED.equals(existing.getState());
            if (!editable) {
                throw new WorkingRevisionExistsException();
            }
        }

        AssetRevision source = asset.getWorkingRevisionId() != null
                ? assetRevisionRepository.findById(asset.getWorkingRevisionId()).orElseThrow()
                : assetRevisionRepository.findById(asset.getApprovedRevisionId())
                    .orElseThrow(() -> new InvalidAssetTransitionException("No approved or editable revision to clone from."));

        int nextNumber = assetRevisionRepository.findByAssetIdOrderByRevisionNumberDesc(assetId).stream()
                .mapToInt(AssetRevision::getRevisionNumber).max().orElse(0) + 1;

        AssetRevision draft = new AssetRevision();
        draft.setAssetId(assetId);
        draft.setRevisionNumber(nextNumber);
        draft.setState(AssetRevision.DRAFT);
        draft.setTitle(source.getTitle());
        draft.setSummary(source.getSummary());
        draft.setProblemStatement(source.getProblemStatement());
        draft.setBusinessImpact(source.getBusinessImpact());
        draft.setSolutionOverview(source.getSolutionOverview());
        draft.setSourceUrl(source.getSourceUrl());
        draft.setAuthoredByUserId(callerId);
        draft.setTags(new HashSet<>(source.getTags()));
        assetRevisionRepository.save(draft);

        asset.setWorkingRevisionId(draft.getId());
        assetRepository.flush();

        AssetRevision lastApproved = asset.getApprovedRevisionId() != null
                ? assetRevisionRepository.findById(asset.getApprovedRevisionId()).orElse(null) : null;
        return AssetResponseMapper.toEditorResponse(asset, draft, lastApproved);
    }

    @Transactional
    public AssetEditorResponse updateWorkingRevision(UUID assetId, String callerId, UpdateAssetRevisionRequest request) {
        validateSourceUrl(request.getSourceUrl());
        Asset asset = assetRepository.lockById(assetId).orElseThrow(() -> new AssetNotFoundException(assetId));
        requireSubmitterOrOwner(asset, callerId);

        if (!asset.getVersion().equals(request.getAssetExpectedVersion())) {
            throw new AssetRevisionStaleException();
        }
        if (asset.getWorkingRevisionId() == null) {
            throw new AssetNotFoundException(assetId);
        }
        AssetRevision working = assetRevisionRepository.findById(asset.getWorkingRevisionId())
                .orElseThrow(() -> new AssetNotFoundException(assetId));
        if (!AssetRevision.DRAFT.equals(working.getState())) {
            throw new InvalidAssetTransitionException("Only a DRAFT revision can be edited.");
        }
        if (!working.getVersion().equals(request.getRevisionExpectedVersion())) {
            throw new AssetRevisionStaleException();
        }

        if (request.getAssetType() != null) {
            if (asset.getApprovedRevisionId() != null) {
                throw new InvalidAssetTransitionException("assetType can only change before the asset has ever been approved.");
            }
            asset.setAssetType(request.getAssetType().getValue());
        }
        if (request.getOwnerUserId() != null) {
            asset.setOwnerUserId(requireActiveInternalUser(request.getOwnerUserId()).getId());
        }
        if (request.getPocId() != null) {
            validatePocAssociation(request.getPocId(), asset.getAssetType());
            asset.setPocId(request.getPocId());
        }

        working.setTitle(request.getTitle());
        working.setSummary(request.getSummary());
        working.setProblemStatement(request.getProblemStatement());
        working.setBusinessImpact(request.getBusinessImpact());
        working.setSolutionOverview(request.getSolutionOverview());
        working.setSourceUrl(request.getSourceUrl());
        working.setTags(resolveTags(request.getTags()));
        assetRepository.flush();

        AssetRevision lastApproved = asset.getApprovedRevisionId() != null
                ? assetRevisionRepository.findById(asset.getApprovedRevisionId()).orElse(null) : null;
        return AssetResponseMapper.toEditorResponse(asset, working, lastApproved);
    }

    @Transactional
    public AssetEditorResponse submitWorkingRevision(UUID assetId, String callerId, Long expectedVersion) {
        Asset asset = assetRepository.lockById(assetId).orElseThrow(() -> new AssetNotFoundException(assetId));
        requireSubmitterOrOwner(asset, callerId);
        if (asset.getWorkingRevisionId() == null) {
            throw new AssetNotFoundException(assetId);
        }
        AssetRevision working = assetRevisionRepository.findById(asset.getWorkingRevisionId())
                .orElseThrow(() -> new AssetNotFoundException(assetId));
        if (!AssetRevision.DRAFT.equals(working.getState())) {
            throw new InvalidAssetTransitionException("Only a DRAFT revision can be submitted.");
        }
        if (!working.getVersion().equals(expectedVersion)) {
            throw new AssetRevisionStaleException();
        }
        working.setState(AssetRevision.PENDING_REVIEW);
        working.setSubmittedAt(Instant.now());
        assetRepository.flush();

        AssetRevision lastApproved = asset.getApprovedRevisionId() != null
                ? assetRevisionRepository.findById(asset.getApprovedRevisionId()).orElse(null) : null;
        return AssetResponseMapper.toEditorResponse(asset, working, lastApproved);
    }

    @Transactional
    public void archiveAsset(UUID assetId, String callerId, Long expectedVersion) {
        Asset asset = assetRepository.lockById(assetId).orElseThrow(() -> new AssetNotFoundException(assetId));
        if (!isSubmitterOrOwner(asset, callerId) && !CurrentUser.isAdmin()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Only the submitter, owner, or an admin can archive this asset.");
        }
        if (!asset.getVersion().equals(expectedVersion)) {
            throw new AssetRevisionStaleException();
        }
        asset.setArchivedAt(Instant.now());
        asset.setArchivedByUserId(callerId);
        assetSearchIndexer.remove(assetId);
    }

    @Transactional(readOnly = true)
    public AssetMinePageResponse listMyAssets(String callerId, int page, int size) {
        // "Owned by", not "submitted by" — submission alone doesn't place an asset in My assets
        // when another employee owns it (docs/specs/asset-hub.md, GET /assets/mine).
        List<Asset> owned = assetRepository.findByOwnerUserIdAndArchivedAtIsNull(callerId).stream()
                .sorted((a, b) -> b.getUpdatedAt().compareTo(a.getUpdatedAt()))
                .toList();

        int fromIndex = Math.min(page * size, owned.size());
        int toIndex = Math.min(fromIndex + size, owned.size());
        List<Asset> pageContent = owned.subList(fromIndex, toIndex);

        List<AssetMineSummaryResponse> content = new ArrayList<>();
        for (Asset asset : pageContent) {
            AssetRevision working = asset.getWorkingRevisionId() != null
                    ? assetRevisionRepository.findById(asset.getWorkingRevisionId()).orElse(null) : null;
            AssetRevision approved = asset.getApprovedRevisionId() != null
                    ? assetRevisionRepository.findById(asset.getApprovedRevisionId()).orElse(null) : null;
            String latestReviewFeedback = working == null ? null
                    : assetReviewRepository.findFirstByRevisionIdOrderByCreatedAtDesc(working.getId())
                            .map(com.sails.ai.selfserviceapi.asset.entity.AssetReview::getFeedback)
                            .orElse(null);
            content.add(AssetResponseMapper.toMineSummaryResponse(asset, working, approved, latestReviewFeedback));
        }
        return AssetResponseMapper.toMinePageResponse(content, page, size, owned.size());
    }

    @Transactional
    public AssetPageResponse listAssets(String q, List<String> types, List<String> tags, String ownerId,
                                         Boolean launchable, int page, int size, String callerId) {
        String normalizedQ = normalizeQuery(q);
        String typesCsv = csv(types);
        String tagsCsv = csv(tags == null ? null : tags.stream().map(AssetLifecycleService::normalizeTagName).toList());
        boolean launchableOnly = Boolean.TRUE.equals(launchable);

        List<UUID> ids = assetSearchRepository.findRankedAssetIds(normalizedQ, typesCsv, tagsCsv, ownerId, launchableOnly, size, page * size);
        long total = assetSearchRepository.countRankedAssets(normalizedQ, typesCsv, tagsCsv, ownerId, launchableOnly);

        List<AssetSummaryResponse> content = hydrateSummaries(ids);
        UUID searchSessionId = normalizedQ != null ? UUID.randomUUID() : null;
        if (searchSessionId != null) {
            AssetEvent event = new AssetEvent();
            event.setUserId(callerId);
            event.setEventType("SEARCH");
            event.setSearchSessionId(searchSessionId);
            assetEventRepository.save(event);
        }
        return AssetResponseMapper.toPageResponse(content, page, size, total, searchSessionId);
    }

    @Transactional(readOnly = true)
    public AssetFacetsResponse getAssetFacets(String q, List<String> types, List<String> tags, String ownerId, Boolean launchable) {
        String normalizedQ = normalizeQuery(q);
        String typesCsv = csv(types);
        String tagsCsv = csv(tags == null ? null : tags.stream().map(AssetLifecycleService::normalizeTagName).toList());
        boolean launchableOnly = Boolean.TRUE.equals(launchable);

        List<AssetFacetsResponseTypesInner> typeCounts = assetSearchRepository
                .countByType(normalizedQ, typesCsv, tagsCsv, ownerId, launchableOnly).stream()
                .map(row -> new AssetFacetsResponseTypesInner(
                        AssetFacetsResponseTypesInner.AssetTypeEnum.fromValue((String) row[0]), ((Number) row[1]).intValue()))
                .toList();
        List<AssetFacetsResponseTagsInner> tagCounts = assetSearchRepository
                .countByTag(normalizedQ, typesCsv, tagsCsv, ownerId, launchableOnly).stream()
                .map(row -> new AssetFacetsResponseTagsInner((String) row[0], ((Number) row[1]).intValue()))
                .toList();
        List<AssetFacetsResponseOwnersInner> ownerCounts = assetSearchRepository
                .countByOwner(normalizedQ, typesCsv, tagsCsv, ownerId, launchableOnly).stream()
                .map(row -> {
                    String userId = (String) row[0];
                    AssetFacetsResponseOwnersInner inner = new AssetFacetsResponseOwnersInner(userId, ((Number) row[1]).intValue());
                    userRepository.findById(userId).ifPresent(u -> inner.ownerDisplayName(AssetResponseMapper.displayName(u)));
                    return inner;
                })
                .toList();
        long launchableCount = assetSearchRepository.countLaunchable(normalizedQ, typesCsv, tagsCsv, ownerId, launchableOnly);

        return AssetResponseMapper.toFacetsResponse(typeCounts, tagCounts, ownerCounts, (int) launchableCount);
    }

    @Transactional
    public AssetFeedbackResponse putFeedback(UUID assetId, String callerId, AssetFeedbackRequest request) {
        Asset asset = requireDiscoverableAsset(assetId);
        AssetFeedback feedback = assetFeedbackRepository.findByAssetIdAndUserId(assetId, callerId)
                .orElseGet(AssetFeedback::new);
        feedback.setAssetId(asset.getId());
        feedback.setUserId(callerId);
        feedback.setRating(request.getRating().getValue());
        feedback.setComment(request.getComment());
        assetFeedbackRepository.save(feedback);
        return AssetResponseMapper.toFeedbackResponse(feedback);
    }

    @Transactional
    public void recordEvent(UUID assetId, String callerId, AssetEventRequest request) {
        requireDiscoverableAsset(assetId);
        AssetEvent event = new AssetEvent();
        event.setAssetId(assetId);
        event.setUserId(callerId);
        event.setEventType(request.getEventType().getValue());
        event.setSearchSessionId(request.getSearchSessionId());
        assetEventRepository.save(event);
    }

    // -- helpers -------------------------------------------------------------------------------

    private List<AssetSummaryResponse> hydrateSummaries(List<UUID> orderedIds) {
        if (orderedIds.isEmpty()) {
            return List.of();
        }
        Map<UUID, Asset> assetsById = assetRepository.findAllById(orderedIds).stream()
                .collect(Collectors.toMap(Asset::getId, a -> a));
        List<UUID> revisionIds = assetsById.values().stream().map(Asset::getApprovedRevisionId).toList();
        Map<UUID, AssetRevision> revisionsById = assetRevisionRepository.findAllById(revisionIds).stream()
                .collect(Collectors.toMap(AssetRevision::getId, r -> r));
        Set<String> ownerIds = assetsById.values().stream().map(Asset::getOwnerUserId).collect(Collectors.toSet());
        Map<String, User> usersById = userRepository.findAllById(ownerIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));
        Set<UUID> pocIds = assetsById.values().stream().map(Asset::getPocId).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, Poc> pocsById = pocRepository.findAllById(pocIds).stream().collect(Collectors.toMap(Poc::getId, p -> p));

        List<AssetSummaryResponse> content = new ArrayList<>();
        for (UUID id : orderedIds) {
            Asset asset = assetsById.get(id);
            if (asset == null) {
                continue;
            }
            AssetRevision approved = revisionsById.get(asset.getApprovedRevisionId());
            String ownerDisplayName = AssetResponseMapper.displayName(usersById.get(asset.getOwnerUserId()));
            Poc poc = asset.getPocId() != null ? pocsById.get(asset.getPocId()) : null;
            content.add(AssetResponseMapper.toSummaryResponse(asset, approved, ownerDisplayName, poc));
        }
        return content;
    }

    private Set<Tag> resolveTags(List<String> names) {
        if (names == null || names.isEmpty()) {
            return new HashSet<>();
        }
        Map<String, String> displayNameByNormalized = new LinkedHashMap<>();
        for (String name : names) {
            String normalized = normalizeTagName(name);
            if (!normalized.isBlank()) {
                displayNameByNormalized.putIfAbsent(normalized, name.trim());
            }
        }

        Set<Tag> resolved = new HashSet<>();
        for (Map.Entry<String, String> entry : displayNameByNormalized.entrySet()) {
            Tag tag = tagRepository.findByNormalizedName(entry.getKey())
                    .orElseGet(() -> {
                        Tag t = new Tag();
                        t.setName(entry.getValue());
                        t.setNormalizedName(entry.getKey());
                        return tagRepository.save(t);
                    });
            resolved.add(tag);
        }
        return resolved;
    }

    private static String normalizeTagName(String name) {
        return name.trim().toLowerCase(Locale.ROOT).replace(",", "");
    }

    private static String normalizeQuery(String q) {
        if (q == null) {
            return null;
        }
        String trimmed = q.trim().replaceAll("\\s+", " ");
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String csv(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return String.join(",", values);
    }

    private static void validateSourceUrl(String sourceUrl) {
        try {
            URI uri = new URI(sourceUrl);
            String scheme = uri.getScheme();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    || uri.getHost() == null || uri.getHost().isBlank()) {
                throw invalidSourceUrl();
            }
        } catch (URISyntaxException | NullPointerException exception) {
            throw invalidSourceUrl();
        }
    }

    private static ApiException invalidSourceUrl() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ASSET_SOURCE_URL",
                "Source URL must be an absolute HTTP(S) URL.");
    }

    private User requireActiveInternalUser(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "User not found: " + userId));
        if (user.getAccountType() != AccountType.INTERNAL || user.getStatus() != UserStatus.ACTIVE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Owner must be an active internal employee.");
        }
        return user;
    }

    private Asset requireDiscoverableAsset(UUID assetId) {
        Asset asset = assetRepository.findById(assetId).orElseThrow(() -> new AssetNotFoundException(assetId));
        if (asset.getArchivedAt() != null || asset.getApprovedRevisionId() == null) {
            throw new AssetNotFoundException(assetId);
        }
        return asset;
    }

    private void validatePocAssociation(UUID pocId, String assetType) {
        if (pocId == null) {
            return;
        }
        if (!"POC".equals(assetType)) {
            throw new InvalidPocAssociationException("pocId is only valid for POC-type assets.");
        }
        if (!CurrentUser.isAdmin()) {
            throw new InvalidPocAssociationException("Only an internal ADMIN may set or change the POC association.");
        }
        Poc poc = pocRepository.findById(pocId)
                .orElseThrow(() -> new InvalidPocAssociationException("Linked POC not found: " + pocId));
        if (poc.getDeletedAt() != null) {
            throw new InvalidPocAssociationException("Linked POC has been deleted: " + pocId);
        }
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
