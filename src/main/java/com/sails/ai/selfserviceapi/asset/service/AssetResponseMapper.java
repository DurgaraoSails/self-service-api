package com.sails.ai.selfserviceapi.asset.service;

import com.sails.ai.selfserviceapi.asset.entity.Asset;
import com.sails.ai.selfserviceapi.asset.entity.AssetAiSuggestion;
import com.sails.ai.selfserviceapi.asset.entity.AssetFeedback;
import com.sails.ai.selfserviceapi.asset.entity.AssetRevision;
import com.sails.ai.selfserviceapi.asset.entity.AssetReview;
import com.sails.ai.selfserviceapi.generated.model.AssetAiSuggestionResponse;
import com.sails.ai.selfserviceapi.generated.model.AssetDetailResponse;
import com.sails.ai.selfserviceapi.generated.model.AssetEditorResponse;
import com.sails.ai.selfserviceapi.generated.model.AssetEditorResponseAsset;
import com.sails.ai.selfserviceapi.generated.model.AssetEditorResponseLastApprovedSummary;
import com.sails.ai.selfserviceapi.generated.model.AssetFacetsResponse;
import com.sails.ai.selfserviceapi.generated.model.AssetFacetsResponseOwnersInner;
import com.sails.ai.selfserviceapi.generated.model.AssetFacetsResponseTagsInner;
import com.sails.ai.selfserviceapi.generated.model.AssetFacetsResponseTypesInner;
import com.sails.ai.selfserviceapi.generated.model.AssetFeedbackResponse;
import com.sails.ai.selfserviceapi.generated.model.AssetLaunch;
import com.sails.ai.selfserviceapi.generated.model.AssetMinePageResponse;
import com.sails.ai.selfserviceapi.generated.model.AssetMineSummaryResponse;
import com.sails.ai.selfserviceapi.generated.model.AssetPageResponse;
import com.sails.ai.selfserviceapi.generated.model.AssetRevisionFields;
import com.sails.ai.selfserviceapi.generated.model.AssetReviewDecisionResponse;
import com.sails.ai.selfserviceapi.generated.model.AssetReviewDetailResponse;
import com.sails.ai.selfserviceapi.generated.model.AssetReviewDetailResponseAsset;
import com.sails.ai.selfserviceapi.generated.model.AssetReviewQueueItemResponse;
import com.sails.ai.selfserviceapi.generated.model.AssetReviewQueuePageResponse;
import com.sails.ai.selfserviceapi.generated.model.AssetReviewerDashboardCategoryResponse;
import com.sails.ai.selfserviceapi.generated.model.AssetReviewerDashboardResponse;
import com.sails.ai.selfserviceapi.generated.model.AssetSummaryResponse;
import com.sails.ai.selfserviceapi.poc.entity.Poc;
import com.sails.ai.selfserviceapi.user.entity.User;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public final class AssetResponseMapper {

    private AssetResponseMapper() {
    }

    public static String displayName(User user) {
        if (user == null) {
            return null;
        }
        if (user.getDisplayName() != null && !user.getDisplayName().isBlank()) {
            return user.getDisplayName();
        }
        return (user.getFirstName() + " " + user.getLastName()).trim();
    }

    public static AssetLaunch toLaunch(Asset asset, Poc poc) {
        AssetLaunch launch = new AssetLaunch(isLaunchable(poc));
        if (asset.getPocId() != null) {
            launch.pocId(asset.getPocId());
        }
        return launch;
    }

    /** Matches PocLaunchService's own readiness check exactly, so "launchable" never drifts from it. */
    public static boolean isLaunchable(Poc poc) {
        return poc != null
                && poc.getDeletedAt() == null
                && Poc.VISIBILITY_ACTIVE.equals(poc.getVisibilityStatus())
                && poc.getAppUrl() != null && !poc.getAppUrl().isBlank();
    }

    public static AssetSummaryResponse toSummaryResponse(Asset asset, AssetRevision approved, String ownerDisplayName, Poc poc) {
        AssetSummaryResponse response = new AssetSummaryResponse(
                asset.getId(),
                AssetSummaryResponse.AssetTypeEnum.fromValue(asset.getAssetType()),
                approved.getTitle(),
                approved.getSummary(),
                asset.getOwnerUserId(),
                tagNames(approved),
                toOffsetDateTime(approved.getUpdatedAt()));
        response.ownerDisplayName(ownerDisplayName);
        response.launch(toLaunch(asset, poc));
        return response;
    }

    public static AssetDetailResponse toDetailResponse(Asset asset, AssetRevision approved, String ownerDisplayName, Poc poc) {
        AssetDetailResponse response = new AssetDetailResponse(
                asset.getId(),
                AssetDetailResponse.AssetTypeEnum.fromValue(asset.getAssetType()),
                approved.getTitle(),
                approved.getSummary(),
                asset.getOwnerUserId(),
                tagNames(approved),
                toOffsetDateTime(approved.getUpdatedAt()),
                approved.getSourceUrl(),
                asset.getSubmittedByUserId(),
                approved.getRevisionNumber(),
                asset.getVersion());
        response.ownerDisplayName(ownerDisplayName);
        response.launch(toLaunch(asset, poc));
        response.problemStatement(approved.getProblemStatement());
        response.businessImpact(approved.getBusinessImpact());
        response.solutionOverview(approved.getSolutionOverview());
        return response;
    }

    public static AssetRevisionFields toRevisionFields(AssetRevision revision) {
        AssetRevisionFields fields = new AssetRevisionFields(
                revision.getId(),
                revision.getRevisionNumber(),
                AssetRevisionFields.StateEnum.fromValue(revision.getState()),
                revision.getTitle(),
                revision.getSummary(),
                revision.getSourceUrl(),
                tagNames(revision),
                revision.getVersion(),
                toOffsetDateTime(revision.getCreatedAt()),
                toOffsetDateTime(revision.getUpdatedAt()));
        fields.problemStatement(revision.getProblemStatement());
        fields.businessImpact(revision.getBusinessImpact());
        fields.solutionOverview(revision.getSolutionOverview());
        if (revision.getSubmittedAt() != null) {
            fields.submittedAt(toOffsetDateTime(revision.getSubmittedAt()));
        }
        return fields;
    }

    public static AssetEditorResponse toEditorResponse(Asset asset, AssetRevision working, AssetRevision lastApproved) {
        AssetEditorResponseAsset assetFields = new AssetEditorResponseAsset(
                asset.getId(),
                AssetEditorResponseAsset.AssetTypeEnum.fromValue(asset.getAssetType()),
                asset.getOwnerUserId(),
                asset.getSubmittedByUserId(),
                asset.getVersion());
        assetFields.pocId(asset.getPocId());
        if (asset.getArchivedAt() != null) {
            assetFields.archivedAt(toOffsetDateTime(asset.getArchivedAt()));
        }

        AssetEditorResponse response = new AssetEditorResponse(assetFields, toRevisionFields(working));
        if (lastApproved != null) {
            response.lastApprovedSummary(new AssetEditorResponseLastApprovedSummary(
                    lastApproved.getRevisionNumber(), lastApproved.getTitle(), lastApproved.getSummary(),
                    toOffsetDateTime(lastApproved.getUpdatedAt())));
        }
        return response;
    }

    public static AssetMineSummaryResponse toMineSummaryResponse(Asset asset, AssetRevision working, AssetRevision approved,
                                                                   String latestReviewFeedback) {
        AssetRevision displayed = working != null ? working : approved;
        String title = displayed.getTitle();
        AssetMineSummaryResponse response = new AssetMineSummaryResponse(
                asset.getId(),
                AssetMineSummaryResponse.AssetTypeEnum.fromValue(asset.getAssetType()),
                title,
                displayed.getRevisionNumber(),
                asset.getVersion(),
                approved != null,
                toOffsetDateTime(displayed.getUpdatedAt()));
        if (working != null && !AssetRevision.APPROVED.equals(working.getState())) {
            response.workingState(AssetMineSummaryResponse.WorkingStateEnum.fromValue(working.getState()));
        }
        response.latestReviewFeedback(latestReviewFeedback);
        return response;
    }

    public static AssetMinePageResponse toMinePageResponse(List<AssetMineSummaryResponse> content, int page, int size, long totalElements) {
        int totalPages = size == 0 ? 0 : (int) Math.ceil((double) totalElements / size);
        return new AssetMinePageResponse(content, page, size, totalElements, totalPages);
    }

    public static AssetPageResponse toPageResponse(List<AssetSummaryResponse> content, int page, int size, long totalElements, java.util.UUID searchSessionId) {
        int totalPages = size == 0 ? 0 : (int) Math.ceil((double) totalElements / size);
        AssetPageResponse response = new AssetPageResponse(content, page, size, totalElements, totalPages);
        response.searchSessionId(searchSessionId);
        return response;
    }

    public static AssetFacetsResponse toFacetsResponse(List<AssetFacetsResponseTypesInner> types,
                                                         List<AssetFacetsResponseTagsInner> tags,
                                                         List<AssetFacetsResponseOwnersInner> owners,
                                                         int launchableCount) {
        return new AssetFacetsResponse(types, tags, owners, launchableCount);
    }

    public static AssetFeedbackResponse toFeedbackResponse(AssetFeedback feedback) {
        AssetFeedbackResponse response = new AssetFeedbackResponse(
                AssetFeedbackResponse.RatingEnum.fromValue(feedback.getRating()),
                toOffsetDateTime(feedback.getUpdatedAt()));
        response.comment(feedback.getComment());
        return response;
    }

    public static AssetReviewQueueItemResponse toReviewQueueItemResponse(Asset asset, AssetRevision revision,
                                                                           String ownerDisplayName, String submitterDisplayName,
                                                                           String authorDisplayName) {
        long ageDays = Duration.between(revision.getSubmittedAt(), Instant.now()).toDays();
        return new AssetReviewQueueItemResponse(
                revision.getId(), asset.getId(),
                AssetReviewQueueItemResponse.AssetTypeEnum.fromValue(asset.getAssetType()),
                revision.getTitle(), asset.getOwnerUserId(), ownerDisplayName,
                asset.getSubmittedByUserId(), submitterDisplayName,
                revision.getAuthoredByUserId(), authorDisplayName, revision.getVersion(),
                toOffsetDateTime(revision.getSubmittedAt()), (int) ageDays);
    }

    public static AssetReviewQueuePageResponse toReviewQueuePageResponse(List<AssetReviewQueueItemResponse> content, int page, int size, long totalElements) {
        int totalPages = size == 0 ? 0 : (int) Math.ceil((double) totalElements / size);
        return new AssetReviewQueuePageResponse(content, page, size, totalElements, totalPages);
    }

    public static AssetReviewDetailResponse toReviewDetailResponse(Asset asset, AssetRevision revision, AssetRevision lastApproved,
                                                                     List<AssetReview> history, String ownerDisplayName,
                                                                     String submitterDisplayName, String authorDisplayName) {
        AssetReviewDetailResponseAsset assetFields = new AssetReviewDetailResponseAsset(
                asset.getId(), AssetReviewDetailResponseAsset.AssetTypeEnum.fromValue(asset.getAssetType()),
                asset.getOwnerUserId(), asset.getSubmittedByUserId(), revision.getAuthoredByUserId());
        assetFields.ownerDisplayName(ownerDisplayName);
        assetFields.submittedByDisplayName(submitterDisplayName);
        assetFields.authoredByDisplayName(authorDisplayName);

        List<AssetReviewDecisionResponse> decisions = history.stream()
                .sorted(Comparator.comparing(AssetReview::getCreatedAt))
                .map(AssetResponseMapper::toReviewDecisionResponse)
                .toList();

        AssetReviewDetailResponse response = new AssetReviewDetailResponse(assetFields, toRevisionFields(revision), decisions);
        if (lastApproved != null) {
            response.lastApprovedRevision(toRevisionFields(lastApproved));
        }
        return response;
    }

    public static AssetReviewDecisionResponse toReviewDecisionResponse(AssetReview review) {
        AssetReviewDecisionResponse response = new AssetReviewDecisionResponse(
                review.getId(), review.getRevisionId(), review.getReviewerUserId(),
                AssetReviewDecisionResponse.DecisionEnum.fromValue(review.getDecision()),
                toOffsetDateTime(review.getCreatedAt()));
        response.feedback(review.getFeedback());
        return response;
    }

    public static AssetReviewerDashboardResponse toDashboardResponse(long total, long approved, long inReview,
                                                                       List<AssetReviewerDashboardCategoryResponse> categories) {
        return new AssetReviewerDashboardResponse(total, approved, inReview, categories);
    }

    /** {@code suggestedTags} is already-parsed JSON — the caller owns deserializing the
     * entity's raw column, keeping this mapper free of an ObjectMapper dependency like every
     * other method here. */
    public static AssetAiSuggestionResponse toAiSuggestionResponse(AssetAiSuggestion run, List<String> suggestedTags) {
        AssetAiSuggestionResponse response = new AssetAiSuggestionResponse(
                run.getId(), run.getRevisionId(),
                AssetAiSuggestionResponse.StatusEnum.fromValue(run.getStatus()),
                toOffsetDateTime(run.getCreatedAt()), toOffsetDateTime(run.getUpdatedAt()));
        response.suggestedTitle(run.getSuggestedTitle());
        response.suggestedSummary(run.getSuggestedSummary());
        response.suggestedTags(suggestedTags);
        response.provider(run.getProvider());
        response.model(run.getModel());
        response.schemaVersion(run.getSchemaVersion());
        response.errorCode(run.getErrorCode());
        return response;
    }

    private static List<String> tagNames(AssetRevision revision) {
        Set<com.sails.ai.selfserviceapi.asset.entity.Tag> tags = revision.getTags();
        return tags.stream().map(com.sails.ai.selfserviceapi.asset.entity.Tag::getName).sorted().toList();
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
