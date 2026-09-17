package com.sails.ai.selfserviceapi.onboarding.generate.cloudbuild;

import com.sails.ai.selfserviceapi.onboarding.generate.GenerationNotice;
import java.util.List;

/**
 * What {@link CloudBuildImporter} found in one cloudbuild.yaml — redacted facts and an overlay,
 * never the raw file. {@code services} is empty for a build-only file (an image-build-and-push
 * pipeline with no {@code gcloud run deploy} step); {@code builds} still feeds Dockerfile paths to
 * the container plan in that case.
 */
public record CloudBuildImport(String sourcePath, List<ImportedService> services, List<ImportedBuild> builds,
                                List<List<String>> preBuildSteps, List<GenerationNotice> notices) {

    public static CloudBuildImport empty(String sourcePath) {
        return new CloudBuildImport(sourcePath, List.of(), List.of(), List.of(), List.of());
    }
}
