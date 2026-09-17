package com.sails.ai.selfserviceapi.onboarding.generate;

import com.sails.ai.selfserviceapi.onboarding.generate.cloudbuild.CloudBuildImport;
import com.sails.ai.selfserviceapi.onboarding.generate.stack.StackKind;
import java.util.List;

/**
 * What {@link com.sails.ai.selfserviceapi.onboarding.generate.stack.StackDetector} and
 * {@link com.sails.ai.selfserviceapi.onboarding.generate.cloudbuild.CloudBuildImporter} already
 * know about a repository, handed to the model as ground truth it must not contradict — the FACTS
 * block in {@code ManifestDraftService}'s user prompt. Redacted by construction: a
 * {@link CloudBuildImport} has already been through {@code EnvVarClassifier}/{@code EvidenceRedactor}
 * by the time it reaches here, so nothing further is needed before this is serialized into a prompt.
 */
public record ManifestFacts(List<ComponentFact> components, CloudBuildImport cloudBuildImport) {

    public static ManifestFacts of(List<ComponentFact> components) {
        return new ManifestFacts(components, null);
    }

    /**
     * @param directory      root as {@code ""}.
     * @param stack          the detected stack, or {@code UNKNOWN}.
     * @param hasDockerfile  whether this directory already has a Dockerfile.
     * @param dockerfilePath the Dockerfile's path, when {@code hasDockerfile} is true.
     */
    public record ComponentFact(String directory, StackKind stack, boolean hasDockerfile, String dockerfilePath) {
    }
}
