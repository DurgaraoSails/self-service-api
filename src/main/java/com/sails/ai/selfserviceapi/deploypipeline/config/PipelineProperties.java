package com.sails.ai.selfserviceapi.deploypipeline.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How this build/deploy actually runs. Defaults describe a real deployment; each can be relaxed
 * to run from a laptop, and each relaxation gives something up — see the field it belongs to.
 */
@ConfigurationProperties(prefix = "pipeline")
public record PipelineProperties(

        /**
         * cloud-build — submits the work to Cloud Build and polls it. The only way this app builds
         *         anything. Works identically whether self-service-api runs on a laptop or on
         *         Cloud Run, because the credentials come from
         *         {@code GoogleCredentials.getApplicationDefault()} either way.
         * skip — does none of it: no GitHub tag, no build, no deploy. Every deploy request is
         *         immediately reported SKIPPED. For teammates running the app locally with no GCP
         *         account at all, so triggering a deploy says it didn't run instead of failing on
         *         credentials.
         */
        String executor,

        /**
         * cloud-build only, and required there. The identity the build runs as, which is
         * load-bearing since the clone step reads its token from Secret Manager: only
         * self-service-builder holds secretAccessor on {@code github-token-<env>}, so blank —
         * meaning Cloud Build's own default service account — cannot clone anything at all. The
         * compact constructor rejects that combination rather than letting every build fail
         * identically at its first step.
         */
        String buildServiceAccount,

        /**
         * This app's own GitHub credential, and only that: {@code GitHubService} uses it for REST
         * API calls — reading a branch's head commit, reading {@code poc.yaml} at that commit, and
         * creating the release tag. Required even for a public repository, since creating a tag is
         * a write.
         *
         * <p>It is never sent to Cloud Build. The clone that happens inside a build reads the same
         * underlying secret straight from Secret Manager instead (see {@code BuildService}), so no
         * literal token is ever written into a build's stored configuration. Supplied like any
         * other credential this app holds: {@code GITHUB_TOKEN} in a deployed environment,
         * {@code secrets/application-local-secrets.yaml} locally.
         */
        String githubToken,

        /**
         * Grants self-service-api's own service account {@code run.invoker} on the service just
         * deployed. Not needed for the default public POC ({@link #allowUnauthenticated}) — a
         * user's browser reaches it directly, self-service-api never sits in that path. This is
         * for the opt-out case: a POC deployed with {@code allowUnauthenticated=false}, where
         * self-service-api still needs to reach it itself. Requires
         * {@code run.services.setIamPolicy}, which {@code roles/editor} deliberately excludes —
         * switch off for a local run without that binding; the deploy is still genuinely
         * verified, only the access grant is skipped.
         */
        boolean grantApiInvoker,

        /**
         * Default security model: {@code true}, meaning POCs are public. The portal iframes a
         * POC's raw Cloud Run URL directly from the user's browser — there is no proxy in front of
         * it — so an IAM-locked service simply cannot be reached at all, portal-launched or not.
         * Identity is instead carried by the short-lived POC-scoped JWT minted at launch, which
         * the POC itself verifies against this API's JWKS. Set {@code false} to lock down one
         * specific sensitive POC; also needs {@code run.services.setIamPolicy}. A locked-down POC
         * can still be reached with an identity token for manual testing —
         * {@code gcloud run services proxy <slug> --region <region>}, or
         * {@code curl -H "Authorization: Bearer $(gcloud auth print-identity-token)" <url>} —
         * which works under a project Editor/Owner role without any IAM grant.
         */
        boolean allowUnauthenticated,

        /** cloud-build only. How long to keep polling one build before giving up on it. */
        Duration buildTimeout,

        /** cloud-build only. Gap between Cloud Build status checks. */
        Duration buildPollInterval,

        /**
         * The branch every POC is deployed from — the branch whose head commit a new version is
         * tagged at.
         *
         * <p>Blank (the default) means each repository's own default branch, whatever GitHub
         * reports it to be. Setting this to e.g. {@code main} forces that branch for every POC
         * instead, which is what you want when repositories disagree about their default and a
         * deploy must always come from the same branch name.
         *
         * <p>It applies to every POC at once, so a repository that genuinely has no branch by this
         * name can no longer be deployed — that failure is reported explicitly by
         * {@code GitHubService.getDeployBranchHeadSha} rather than as a bare 404, because the cause
         * is this setting rather than anything about the repository.
         */
        String deployBranch
) {

    private static final String CLOUD_BUILD = "cloud-build";
    private static final String SKIP = "skip";

    /**
     * Rejects a pipeline configuration this app cannot honour, at startup rather than at the first
     * deploy. Both checks here guard the same failure shape: a value the app accepts happily and
     * then cannot act on, discovered only once someone is waiting on a deployment.
     *
     * <p>Spring picks the executor bean with {@code @ConditionalOnProperty}, which has no way to
     * express "fail on anything else" — an unrecognised value simply matches no condition. Since
     * {@code SkippingPipelineExecutor} is the {@code matchIfMissing} fallback, a stale
     * {@code PIPELINE_EXECUTOR=local} left over from before that mode was removed would otherwise
     * start cleanly and quietly skip every deploy, reporting SKIPPED for work an operator believes
     * is running. Failing here turns that into one unmissable line at boot.
     *
     * <p>The build service account became mandatory under cloud-build when {@code BuildService}
     * started reading the clone token from Secret Manager unconditionally. Blank is not a weaker
     * fallback there, it is a dead end: Cloud Build's own default service account holds no
     * secretAccessor grant on the secret, so every build fails at its first step with a Cloud Build
     * error that names the secret rather than the property that was left unset.
     */
    public PipelineProperties {
        if (executor != null && !CLOUD_BUILD.equalsIgnoreCase(executor) && !SKIP.equalsIgnoreCase(executor)) {
            throw new IllegalArgumentException("pipeline.executor must be '" + CLOUD_BUILD + "' or '" + SKIP
                    + "', but was '" + executor + "'. The 'local' executor was removed — every build now runs"
                    + " in Cloud Build.");
        }
        if (CLOUD_BUILD.equalsIgnoreCase(executor) && (buildServiceAccount == null || buildServiceAccount.isBlank())) {
            throw new IllegalArgumentException("pipeline.build-service-account must be set when pipeline.executor"
                    + " is '" + CLOUD_BUILD + "'. Blank runs every build as Cloud Build's own default service"
                    + " account, which holds no secretAccessor grant on the github-token secret, so every clone"
                    + " step would fail resolving GITHUB_TOKEN. Set it to the account that does hold that grant"
                    + " (self-service-builder, per self-service-terraform).");
        }
    }

    public boolean isCloudBuild() {
        return CLOUD_BUILD.equalsIgnoreCase(executor);
    }

    /**
     * Absent counts as skip. {@code SkippingPipelineExecutor} claims that case with
     * {@code matchIfMissing}, and this is the other half of the same decision — if the two
     * disagree the app starts, {@code PocDeploymentService} and {@code PipelineRunner} both take
     * the real deploy path, a release tag is written to the POC's repository, and only then does
     * the skipping executor throw, leaving a tag that blocks reusing that version label.
     *
     * <p>Blank is not handled here because it never gets this far: the compact constructor rejects
     * it, which is the better outcome — a property that is present but empty is a mistake worth
     * naming at boot rather than quietly reading as "don't deploy".
     */
    public boolean isSkip() {
        return executor == null || SKIP.equalsIgnoreCase(executor);
    }

    /** Blank means "follow each repository's own default branch" — see {@link #deployBranch}. */
    public boolean hasDeployBranch() {
        return deployBranch != null && !deployBranch.isBlank();
    }

    public boolean hasGithubToken() {
        return githubToken != null && !githubToken.isBlank();
    }

    public boolean usesCustomBuildServiceAccount() {
        return buildServiceAccount != null && !buildServiceAccount.isBlank();
    }

}
