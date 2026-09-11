package com.sails.ai.selfserviceapi.asset.ai;

/**
 * Boundary between Asset Hub and whichever metadata-suggestion model is configured. A bean of
 * this type is only registered when {@code asset-hub.ai-enabled=true} (see
 * {@code asset/config/AssetAiClientConfig}); its absence is how the suggestion service degrades
 * to {@code ASSET_AI_UNAVAILABLE} with no provider-specific branching elsewhere.
 */
public interface AssetAiProvider {

    /** Recorded on {@code asset_ai_suggestions.provider}, e.g. "gemini". */
    String providerName();

    /** Recorded on {@code asset_ai_suggestions.model}. */
    String modelName();

    /**
     * Calls the model with only {@code input}'s bounded fields — never a source URL, POC data, or
     * prior AI output. Throws {@link AssetAiProviderException} on timeout, transport failure, or an
     * invalid/unparseable response; never returns partial or null-field results.
     */
    AssetAiSuggestionResult suggest(AssetAiSuggestionInput input);
}
