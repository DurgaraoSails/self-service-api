package com.sails.ai.selfserviceapi.onboarding.generate;

import com.sails.ai.selfserviceapi.deploypipeline.manifest.PocManifest;
import java.util.List;

/**
 * A draft that passed {@code ManifestValidator} — the only shape {@link ManifestDraftService}
 * returns successfully. A draft that never validates, even after repair attempts, is a
 * {@link ManifestDraftValidationException} instead, never a partial result.
 *
 * @param manifest      the validated manifest.
 * @param dockerfiles   Dockerfiles proposed for containers that declared none — advisory, see
 *                      {@link GeneratedDockerfile}.
 * @param assumptions   what the model assumed, for the human reviewing this before committing it.
 * @param secretWarnings credential-shaped literals {@link RepoInventoryService}'s evidence
 *                      contained — never turned into a manifest entry, only surfaced so a team
 *                      knows to rotate/remove them.
 */
public record ManifestDraftResult(
        PocManifest manifest,
        List<GeneratedDockerfile> dockerfiles,
        List<String> assumptions,
        List<String> secretWarnings
) {
}
