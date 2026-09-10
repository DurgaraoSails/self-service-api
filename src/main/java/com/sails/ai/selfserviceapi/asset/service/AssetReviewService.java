package com.sails.ai.selfserviceapi.asset.service;

import com.sails.ai.selfserviceapi.asset.entity.Asset;
import com.sails.ai.selfserviceapi.asset.entity.AssetRevision;
import com.sails.ai.selfserviceapi.asset.entity.AssetReview;
import com.sails.ai.selfserviceapi.asset.exception.AssetRevisionNotFoundException;
import com.sails.ai.selfserviceapi.asset.exception.AssetRevisionStaleException;
import com.sails.ai.selfserviceapi.asset.exception.InvalidAssetTransitionException;
import com.sails.ai.selfserviceapi.asset.exception.SelfReviewForbiddenException;
import com.sails.ai.selfserviceapi.asset.repository.AssetRepository;
import com.sails.ai.selfserviceapi.asset.repository.AssetRevisionRepository;
import com.sails.ai.selfserviceapi.asset.repository.AssetReviewRepository;
import com.sails.ai.selfserviceapi.asset.search.AssetSearchIndexer;
import com.sails.ai.selfserviceapi.common.exception.ApiException;
import com.sails.ai.selfserviceapi.generated.model.AssetReviewDetailResponse;
import com.sails.ai.selfserviceapi.generated.model.AssetReviewQueueItemResponse;
import com.sails.ai.selfserviceapi.generated.model.AssetReviewQueuePageResponse;
import com.sails.ai.selfserviceapi.generated.model.AssetReviewerDashboardCategoryResponse;
import com.sails.ai.selfserviceapi.generated.model.AssetReviewerDashboardResponse;
import com.sails.ai.selfserviceapi.generated.model.CreateAssetReviewRequest;
import com.sails.ai.selfserviceapi.user.entity.User;
import com.sails.ai.selfserviceapi.user.repository.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AssetReviewService {

    private static final List<String> ASSET_TYPES =
            List.of("AI_USE_CASE", "POC", "BLOG", "ARTICLE", "HACKATHON_IDEA", "DOCUMENT");

    private final AssetRepository assetRepository;
    private final AssetRevisionRepository assetRevisionRepository;
    private final AssetReviewRepository assetReviewRepository;
    private final UserRepository userRepository;
    private final AssetSearchIndexer assetSearchIndexer;

    public AssetReviewService(AssetRepository assetRepository, AssetRevisionRepository assetRevisionRepository,
                               AssetReviewRepository assetReviewRepository, UserRepository userRepository,
                               AssetSearchIndexer assetSearchIndexer) {
        this.assetRepository = assetRepository;
        this.assetRevisionRepository = assetRevisionRepository;
        this.assetReviewRepository = assetReviewRepository;
        this.userRepository = userRepository;
        this.assetSearchIndexer = assetSearchIndexer;
    }

    @Transactional(readOnly = true)
    public AssetReviewQueuePageResponse listAssetReviews(List<String> types, Integer maxAgeDays, int page, int size) {
        Instant cutoff = maxAgeDays != null ? Instant.now().minus(java.time.Duration.ofDays(maxAgeDays)) : null;
        String typesCsv = (types == null || types.isEmpty()) ? null : String.join(",", types);
        List<AssetRevision> pending = assetRevisionRepository.findPendingQueue(typesCsv, cutoff, size, page * size);
        long total = assetRevisionRepository.countPendingQueue(typesCsv, cutoff);

        Set<UUID> assetIds = pending.stream().map(AssetRevision::getAssetId).collect(Collectors.toSet());
        Map<UUID, Asset> assetsById = assetRepository.findAllById(assetIds).stream()
                .collect(Collectors.toMap(Asset::getId, a -> a));
        Set<String> userIds = new java.util.HashSet<>();
        assetsById.values().forEach(a -> {
            userIds.add(a.getOwnerUserId());
            userIds.add(a.getSubmittedByUserId());
        });
        Map<String, User> usersById = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));

        List<AssetReviewQueueItemResponse> content = pending.stream()
                .map(revision -> {
                    Asset asset = assetsById.get(revision.getAssetId());
                    String ownerName = AssetResponseMapper.displayName(usersById.get(asset.getOwnerUserId()));
                    String submitterName = AssetResponseMapper.displayName(usersById.get(asset.getSubmittedByUserId()));
                    return AssetResponseMapper.toReviewQueueItemResponse(asset, revision, ownerName, submitterName);
                })
                .toList();

        return AssetResponseMapper.toReviewQueuePageResponse(content, page, size, total);
    }

    @Transactional(readOnly = true)
    public AssetReviewDetailResponse getAssetReviewDetail(UUID revisionId) {
        AssetRevision revision = assetRevisionRepository.findById(revisionId)
                .orElseThrow(() -> new AssetRevisionNotFoundException(revisionId));
        Asset asset = assetRepository.findById(revision.getAssetId())
                .orElseThrow(() -> new AssetRevisionNotFoundException(revisionId));
        return buildReviewDetail(asset, revision);
    }

    @Transactional
    public AssetReviewDetailResponse createAssetReviewDecision(UUID revisionId, String reviewerId, CreateAssetReviewRequest request) {
        AssetRevision revision = assetRevisionRepository.findById(revisionId)
                .orElseThrow(() -> new AssetRevisionNotFoundException(revisionId));
        Asset asset = assetRepository.lockById(revision.getAssetId())
                .orElseThrow(() -> new AssetRevisionNotFoundException(revisionId));
        // Re-read the revision inside the lock so a concurrent decision on the same revision loses the race.
        revision = assetRevisionRepository.findById(revisionId).orElseThrow(() -> new AssetRevisionNotFoundException(revisionId));

        if (!AssetRevision.PENDING_REVIEW.equals(revision.getState())) {
            throw new InvalidAssetTransitionException("This revision is no longer pending review.");
        }
        if (!revision.getVersion().equals(request.getExpectedVersion())) {
            throw new AssetRevisionStaleException();
        }
        if (asset.getSubmittedByUserId().equals(reviewerId) || revision.getAuthoredByUserId().equals(reviewerId)) {
            throw new SelfReviewForbiddenException();
        }

        String decision = request.getDecision().getValue();
        boolean feedbackRequired = !"APPROVE".equals(decision);
        if (feedbackRequired && (request.getFeedback() == null || request.getFeedback().isBlank())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    "feedback is required when requesting changes or rejecting.");
        }

        switch (decision) {
            case "APPROVE" -> approve(asset, revision);
            case "REQUEST_CHANGES" -> revision.setState(AssetRevision.CHANGES_REQUESTED);
            case "REJECT" -> revision.setState(AssetRevision.REJECTED);
            default -> throw new IllegalStateException("Unreachable: closed enum");
        }

        AssetReview review = new AssetReview();
        review.setRevisionId(revisionId);
        review.setReviewerUserId(reviewerId);
        review.setDecision(decision);
        review.setFeedback(request.getFeedback());
        assetReviewRepository.save(review);
        // Same reasoning as AssetLifecycleService's flush calls: the revision/asset state changes
        // above are otherwise deferred UPDATEs, and their @Version bump wouldn't be reflected in
        // the response built immediately after.
        assetRepository.flush();

        return buildReviewDetail(asset, revision);
    }

    @Transactional(readOnly = true)
    public AssetReviewerDashboardResponse getAssetReviewerDashboard() {
        List<Asset> assets = assetRepository.findByArchivedAtIsNull();
        Set<UUID> workingRevisionIds = assets.stream().map(Asset::getWorkingRevisionId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, AssetRevision> workingRevisionsById = assetRevisionRepository.findAllById(workingRevisionIds).stream()
                .collect(Collectors.toMap(AssetRevision::getId, r -> r));

        Map<String, long[]> countsByType = new java.util.HashMap<>();
        for (String type : ASSET_TYPES) {
            countsByType.put(type, new long[3]);
        }
        long total = 0;
        long approved = 0;
        long inReview = 0;
        for (Asset asset : assets) {
            long[] counts = countsByType.computeIfAbsent(asset.getAssetType(), t -> new long[3]);
            counts[0]++;
            total++;
            if (asset.getApprovedRevisionId() != null) {
                counts[1]++;
                approved++;
            }
            AssetRevision working = asset.getWorkingRevisionId() != null ? workingRevisionsById.get(asset.getWorkingRevisionId()) : null;
            if (working != null && AssetRevision.PENDING_REVIEW.equals(working.getState())) {
                counts[2]++;
                inReview++;
            }
        }

        List<AssetReviewerDashboardCategoryResponse> categories = new ArrayList<>();
        for (String type : ASSET_TYPES) {
            long[] counts = countsByType.get(type);
            categories.add(new AssetReviewerDashboardCategoryResponse(
                    AssetReviewerDashboardCategoryResponse.AssetTypeEnum.fromValue(type), counts[0], counts[1], counts[2]));
        }

        return AssetResponseMapper.toDashboardResponse(total, approved, inReview, categories);
    }

    private void approve(Asset asset, AssetRevision revision) {
        if (asset.getApprovedRevisionId() != null && !asset.getApprovedRevisionId().equals(revision.getId())) {
            assetRevisionRepository.findById(asset.getApprovedRevisionId())
                    .ifPresent(previous -> previous.setState(AssetRevision.SUPERSEDED));
        }
        revision.setState(AssetRevision.APPROVED);
        asset.setApprovedRevisionId(revision.getId());
        // APPROVED is not one of the "active working" states — the asset has nothing pending now.
        asset.setWorkingRevisionId(null);

        String ownerDisplayName = userRepository.findById(asset.getOwnerUserId())
                .map(AssetResponseMapper::displayName).orElse(null);
        assetSearchIndexer.index(asset, revision, ownerDisplayName);
    }

    private AssetReviewDetailResponse buildReviewDetail(Asset asset, AssetRevision revision) {
        AssetRevision lastApproved = asset.getApprovedRevisionId() != null && !asset.getApprovedRevisionId().equals(revision.getId())
                ? assetRevisionRepository.findById(asset.getApprovedRevisionId()).orElse(null) : null;
        List<AssetReview> history = assetReviewRepository.findByRevisionIdOrderByCreatedAtAsc(revision.getId());
        String ownerName = userRepository.findById(asset.getOwnerUserId()).map(AssetResponseMapper::displayName).orElse(null);
        String submitterName = userRepository.findById(asset.getSubmittedByUserId()).map(AssetResponseMapper::displayName).orElse(null);
        return AssetResponseMapper.toReviewDetailResponse(asset, revision, lastApproved, history, ownerName, submitterName);
    }
}
