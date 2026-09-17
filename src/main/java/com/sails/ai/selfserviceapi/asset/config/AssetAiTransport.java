package com.sails.ai.selfserviceapi.asset.config;

/**
 * How Asset Hub reaches Gemini. Both carry the same {@code generateContent} request/response body,
 * including {@code responseSchema} structured output — only the host, path, and credential differ,
 * which is why one provider implementation serves both.
 */
public enum AssetAiTransport {

    /**
     * Gemini through Vertex AI, authenticated with Application Default Credentials (the same
     * mechanism the deploy pipeline uses for Cloud Build/Cloud Run). Needs a GCP project and either
     * {@code gcloud auth application-default login} locally or an attached service account in a
     * deploy. No API key to store or rotate; billing and audit go through the GCP project.
     */
    VERTEX_AI,

    /**
     * Gemini through the Google AI (generativelanguage) endpoint, authenticated with an API key.
     * Needs no GCP project, gcloud, or ADC — so it runs for a developer with only a key from AI
     * Studio, and in environments where ADC cannot be provisioned. The key is a long-lived
     * credential: supply it through the existing deployment secret mechanism, never in committed
     * config.
     */
    GEMINI_API
}
