package com.sails.ai.selfserviceapi.asset.ai;

import java.util.List;

/**
 * The canonical, bounded fields sent to an {@link AssetAiProvider}. Deliberately excludes
 * sourceUrl, POC runtime data, user profile data, review feedback, and prior AI output — see
 * docs/specs/asset-hub.md's AI Suggestion Contract. Field order is fixed because callers hash this
 * record's canonical JSON serialization to detect a stale draft.
 */
public record AssetAiSuggestionInput(
        String assetType,
        String title,
        String summary,
        String problemStatement,
        String businessImpact,
        String solutionOverview,
        List<String> tags
) {
}
