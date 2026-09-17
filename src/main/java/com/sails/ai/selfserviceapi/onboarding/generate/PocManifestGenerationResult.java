package com.sails.ai.selfserviceapi.onboarding.generate;

import com.sails.ai.selfserviceapi.generated.model.PocOnboardingSeverity;
import com.sails.ai.selfserviceapi.onboarding.OnboardingCheckResult;
import java.util.List;

/**
 * The three honest outcomes of asking for a poc.yaml, plus whatever the checker already knows about
 * the same repository — attached regardless of outcome, so the portal always has findings to show.
 *
 * <p>{@code files}/{@code notices}/{@code imports} are the canonical data; {@link #pocYaml()},
 * {@link #dockerfiles()} and {@link #warnings()} are legacy views derived from them for
 * {@code PocOnboardingController} to map onto the OpenAPI response's deprecated fields, kept for
 * one release rather than stored a second time.
 *
 * @param outcome              which of the three cases this was.
 * @param files                every file this run produced — poc.yaml first (when present), then
 *                             the rest by path. Empty outside {@link Outcome#GENERATED}, except the
 *                             platform's own template file on {@link Outcome#UNAVAILABLE}.
 * @param notices              everything worth a team's attention, at whatever severity it is —
 *                             import decisions, lint findings, a truncated tree, why generation was
 *                             unavailable.
 * @param imports              what was read from a cloudbuild.yaml, when the repository had one and
 *                             it applied to this outcome.
 * @param assumptions          what the model assumed, for {@link Outcome#GENERATED} only.
 * @param manifestWasCorrected true when the repo already had a poc.yaml that failed the checker and
 *                             this is a correction of it, rather than a manifest for a repo with none.
 * @param checkResult          the same result {@code /poc-onboarding/check} would return for this
 *                             repository and branch — never omitted, so a degraded or "not needed"
 *                             outcome still carries something actionable.
 */
public record PocManifestGenerationResult(
        Outcome outcome,
        List<GeneratedFile> files,
        List<GenerationNotice> notices,
        List<GenerationImport> imports,
        List<String> assumptions,
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

    /** Legacy view: the poc.yaml file's content — the platform template on UNAVAILABLE, null on NOT_NEEDED. */
    public String pocYaml() {
        return files.stream()
                .filter(f -> f.kind() == GeneratedFile.Kind.POC_YAML)
                .map(GeneratedFile::content)
                .findFirst().orElse(null);
    }

    /** Legacy view: every file of kind DOCKERFILE. */
    public List<GeneratedFile> dockerfiles() {
        return files.stream().filter(f -> f.kind() == GeneratedFile.Kind.DOCKERFILE).toList();
    }

    /** Legacy view: the messages of every WARNING or ERROR notice — INFO notices are new information this field never carried. */
    public List<String> warnings() {
        return notices.stream()
                .filter(n -> n.severity() == PocOnboardingSeverity.WARNING || n.severity() == PocOnboardingSeverity.ERROR)
                .map(GenerationNotice::message)
                .toList();
    }

    static PocManifestGenerationResult notNeeded(OnboardingCheckResult checkResult, String reason) {
        return new PocManifestGenerationResult(Outcome.NOT_NEEDED, List.of(),
                List.of(GenerationNotice.info(GenerationNoticeCode.IMPORT_NOT_APPLIED, reason)), List.of(), List.of(),
                false, checkResult);
    }

    static PocManifestGenerationResult unavailable(OnboardingCheckResult checkResult, String reason, String templateYaml) {
        List<GeneratedFile> files = templateYaml == null ? List.of()
                : List.of(new GeneratedFile("poc.yaml", GeneratedFile.Kind.POC_YAML, GeneratedFile.Action.CREATE,
                        GeneratedFile.Source.TEMPLATE, null, templateYaml,
                        "Generation was unavailable — this is the platform's own template.", false));
        return new PocManifestGenerationResult(Outcome.UNAVAILABLE, files,
                List.of(GenerationNotice.warning(GenerationNoticeCode.MODEL_UNAVAILABLE, reason)), List.of(), List.of(),
                false, checkResult);
    }
}
