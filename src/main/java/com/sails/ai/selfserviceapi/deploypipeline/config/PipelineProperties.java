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
         * local — clones, builds and deploys on THIS machine using your own gcloud credentials.
         *         Needs git, docker and gcloud installed. For development.
         * cloud-build — submits the work to Cloud Build and polls it. For production; the only
         *         option that works once self-service-api itself runs on Cloud Run, which has no
         *         Docker daemon.
         * skip — does none of the above: no GitHub tag, no build, no deploy. Every deploy request
         *         is immediately reported SKIPPED. For teammates running the app locally with no
         *         GCP account/credentials, so triggering a deploy never fails with a confusing
         *         "docker: command not found"-style error — it just says it didn't run.
         */
        String executor,

        /**
         * cloud-build only. Identity the build runs as. Blank runs it as Cloud Build's own
         * default service account — useful while self-service-builder holds none of its intended
         * IAM bindings yet.
         */
        String buildServiceAccount,

        /**
         * cloud-build only. Secret Manager secret id holding the GitHub token, resolved inside
         * the build so it is never stored on the Build resource. Blank falls back to
         * {@link #githubToken()}.
         */
        String githubTokenSecretId,

        /**
         * Used by GitHubService (both executors, to create release tags via the GitHub API) and
         * by the local executor's clone. For cloud-build's own clone step, prefer
         * {@link #githubTokenSecretId()} — an inline token there is stored permanently on the
         * Build resource. Leave blank for a public repository.
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

        /** local only. Blank uses the system temp directory. */
        String workspaceDir,

        /** local only. How long any single git/docker/gcloud command may take. */
        Duration commandTimeout,

        /** cloud-build only. How long to keep polling one build before giving up on it. */
        Duration buildTimeout,

        /** cloud-build only. Gap between Cloud Build status checks. */
        Duration buildPollInterval
) {

    public boolean isCloudBuild() {
        return "cloud-build".equalsIgnoreCase(executor);
    }

    public boolean isSkip() {
        return "skip".equalsIgnoreCase(executor);
    }

    public boolean usesSecretManagerToken() {
        return githubTokenSecretId != null && !githubTokenSecretId.isBlank();
    }

    public boolean hasGithubToken() {
        return githubToken != null && !githubToken.isBlank();
    }

    public boolean usesCustomBuildServiceAccount() {
        return buildServiceAccount != null && !buildServiceAccount.isBlank();
    }
}
