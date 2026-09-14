package com.sails.ai.selfserviceapi.onboarding.generate;

import com.sails.ai.selfserviceapi.onboarding.OnboardingCheckResult;
import java.util.List;

/**
 * The three honest outcomes of asking for a poc.yaml, plus whatever the checker already knows about
 * the same repository — attached regardless of outcome, so the portal always has findings to show.
 *
 * @param outcome              which of the three cases this was.
 * @param pocYaml              the rendered manifest text for {@link Outcome#GENERATED}; the
 *                             platform's own shipped {@code docs/poc.yaml.template} for
 *                             {@link Outcome#UNAVAILABLE}, so there is always something to copy;
 *                             null for {@link Outcome#NOT_NEEDED}.
 * @param dockerfiles          Dockerfiles proposed for containers that declared none — advisory,
 *                             see {@link GeneratedDockerfile}. Empty outside {@link Outcome#GENERATED}.
 * @param assumptions          what the model assumed, for {@link Outcome#GENERATED} only.
 * @param warnings             anything worth a team's attention that isn't a manifest violation —
 *                             a committed secret literal, a truncated repository tree, why
 *                             generation was unavailable.
 * @param manifestWasCorrected true when the repo already had a poc.yaml that failed the checker and
 *                             this is a correction of it, rather than a manifest for a repo with none.
 * @param checkResult          the same result {@code /poc-onboarding/check} would return for this
 *                             repository and branch — never omitted, so a degraded or "not needed"
 *                             outcome still carries something actionable.
 */
public record PocManifestGenerationResult(
        Outcome outcome,
        String pocYaml,
        List<GeneratedDockerfile> dockerfiles,
        List<String> assumptions,
        List<String> warnings,
        boolean manifestWasCorrected,
        OnboardingCheckResult checkResult
) {

    public enum Outcome {
        /** A root Dockerfile and nothing else, or an existing poc.yaml that already passes the checker. */
        NOT_NEEDED,
        GENERATED,
        /** No model reachable, generation disabled, the repo isn't accessible, or every repair attempt failed. */
        UNAVAILABLE
    }

    static PocManifestGenerationResult notNeeded(OnboardingCheckResult checkResult, String reason) {
        return new PocManifestGenerationResult(Outcome.NOT_NEEDED, null, List.of(), List.of(), List.of(reason),
                false, checkResult);
    }

    static PocManifestGenerationResult unavailable(OnboardingCheckResult checkResult, String reason, String templateYaml) {
        return new PocManifestGenerationResult(Outcome.UNAVAILABLE, templateYaml, List.of(), List.of(), List.of(reason),
                false, checkResult);
    }
}
