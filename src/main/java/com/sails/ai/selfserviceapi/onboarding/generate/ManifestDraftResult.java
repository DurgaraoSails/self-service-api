package com.sails.ai.selfserviceapi.onboarding.generate;

import com.sails.ai.selfserviceapi.deploypipeline.manifest.PocManifest;
import java.util.List;

/**
 * A draft that passed {@code ManifestValidator} (with the overlay already applied) — the only
 * shape {@code ManifestDraftService} returns successfully. A draft that never validates, even
 * after repair attempts, is a {@link ManifestDraftValidationException} instead, never a partial
 * result.
 *
 * <p>Dockerfiles are no longer part of this — {@code ManifestDraftService} is manifest-only as of
 * Phase 3; {@code DockerfilePlanner}/{@code DockerfileDraftService} decide those separately, per
 * container, only for containers that actually need one.
 *
 * @param manifest    the validated manifest.
 * @param assumptions what the model assumed, for the human reviewing this before committing it.
 * @param notices     anything worth surfacing from the draft itself — a credential-shaped literal
 *                    found in the evidence and redacted before it ever reached the prompt
 *                    ({@code EVIDENCE_SECRET_REDACTED}), most commonly.
 */
public record ManifestDraftResult(PocManifest manifest, List<String> assumptions, List<GenerationNotice> notices) {
}
