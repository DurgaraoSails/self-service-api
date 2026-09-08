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
         *
         * <p>There used to be a third mode, {@code local}, that cloned/built/deployed with
         * subprocesses on the developer's own machine. It was removed: it needed docker and gcloud
         * installed to do what Cloud Build already does, and it kept drifting from the Cloud Build
         * path it was supposed to mirror — its flag ordering would have failed every
         * multi-container deploy, undetected because no multi-container deploy ever ran through it.
         * Anything other than the two values above is rejected at startup (see the constructor)
         * rather than silently matching no executor bean and behaving like {@code skip}.
         */
        String executor,

        /**
         * cloud-build only. Identity the build runs as. Blank runs it as Cloud Build's own
         * default service account — useful while self-service-builder holds none of its intended
         * IAM bindings yet.
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
        Duration buildPollInterval
) {

    private static final String CLOUD_BUILD = "cloud-build";
    private static final String SKIP = "skip";

    /**
     * Rejects an executor this app cannot honour, at startup rather than at the first deploy.
     *
     * <p>Spring picks the executor bean with {@code @ConditionalOnProperty}, which has no way to
     * express "fail on anything else" — an unrecognised value simply matches no condition. Since
     * {@code SkippingPipelineExecutor} is the {@code matchIfMissing} fallback, a stale
     * {@code PIPELINE_EXECUTOR=local} left over from before that mode was removed would otherwise
     * start cleanly and quietly skip every deploy, reporting SKIPPED for work an operator believes
     * is running. Failing here turns that into one unmissable line at boot.
     */
    public PipelineProperties {
        if (executor != null && !CLOUD_BUILD.equalsIgnoreCase(executor) && !SKIP.equalsIgnoreCase(executor)) {
            throw new IllegalArgumentException("pipeline.executor must be '" + CLOUD_BUILD + "' or '" + SKIP
                    + "', but was '" + executor + "'. The 'local' executor was removed — every build now runs"
                    + " in Cloud Build.");
        }
    }

    public boolean isCloudBuild() {
        return CLOUD_BUILD.equalsIgnoreCase(executor);
    }

    public boolean isSkip() {
        return SKIP.equalsIgnoreCase(executor);
    }

    public boolean hasGithubToken() {
        return githubToken != null && !githubToken.isBlank();
    }

    public boolean usesCustomBuildServiceAccount() {
        return buildServiceAccount != null && !buildServiceAccount.isBlank();
    }

}
