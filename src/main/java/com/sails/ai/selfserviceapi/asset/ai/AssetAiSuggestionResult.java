package com.sails.ai.selfserviceapi.asset.ai;

import java.util.List;

/** Schema-validated structured output from an {@link AssetAiProvider} for one suggestion run. */
public record AssetAiSuggestionResult(
        String suggestedTitle,
        String suggestedSummary,
        List<String> suggestedTags
) {
}
