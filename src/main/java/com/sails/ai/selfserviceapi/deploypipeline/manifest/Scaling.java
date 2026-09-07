package com.sails.ai.selfserviceapi.deploypipeline.manifest;

/**
 * A POC's {@code gcloud run deploy --min-instances}/{@code --max-instances}. Either may be
 * {@code null} — Cloud Run's own default (0 min, 100 max) applies, so a manifest with no
 * {@code scaling} block deploys exactly as it did before this field existed.
 */
public record Scaling(Integer min, Integer max) {

    public static Scaling none() {
        return new Scaling(null, null);
    }
}
