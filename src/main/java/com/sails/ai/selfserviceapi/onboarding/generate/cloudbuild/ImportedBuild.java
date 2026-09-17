package com.sails.ai.selfserviceapi.onboarding.generate.cloudbuild;

import java.util.List;

/**
 * One {@code docker build}/{@code kaniko}/{@code pack build} step — links a Dockerfile/context to
 * the image reference(s) it produced, so a later {@code gcloud run deploy --image} can be matched
 * back to the Dockerfile that built it.
 *
 * @param buildArgNames names only — plan's BUILD_ARG_UNSUPPORTED notice explains why values aren't kept.
 * @param target        the {@code --target} stage name, when the build pinned one (unsupported today).
 */
public record ImportedBuild(String dockerfile, String context, List<String> imageRefs,
                             List<String> buildArgNames, String target) {
}
