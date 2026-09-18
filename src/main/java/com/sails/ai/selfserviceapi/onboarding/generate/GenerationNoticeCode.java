package com.sails.ai.selfserviceapi.onboarding.generate;

/**
 * Every stable notice code the generation pipeline can emit, in one place — the shared vocabulary
 * {@code EnvVarClassifier}, {@code CloudBuildImporter}, {@code DockerfileLinter}/{@code DockerfilePlanner}
 * and {@code PocManifestGenerationService} all draw from, rather than each inventing its own.
 * {@code GenerationNotice.code} is a plain string, not an enum, so a new code here never breaks a
 * client parsing the response — see {@code PocGenerationNotice} in the OpenAPI spec.
 */
public final class GenerationNoticeCode {

    private GenerationNoticeCode() {
    }

    public static final String TREE_TRUNCATED = "TREE_TRUNCATED";
    public static final String EVIDENCE_SECRET_REDACTED = "EVIDENCE_SECRET_REDACTED";
    public static final String MODEL_UNAVAILABLE = "MODEL_UNAVAILABLE";

    public static final String CLOUDBUILD_IMPORTED = "CLOUDBUILD_IMPORTED";
    public static final String CLOUDBUILD_PARSE_FAILED = "CLOUDBUILD_PARSE_FAILED";
    public static final String CLOUDBUILD_STEP_NOT_UNDERSTOOD = "CLOUDBUILD_STEP_NOT_UNDERSTOOD";
    public static final String CLOUD_RUN_YAML_NOT_IMPORTED = "CLOUD_RUN_YAML_NOT_IMPORTED";
    public static final String OTHER_DEPLOY_TARGET = "OTHER_DEPLOY_TARGET";
    public static final String IMPORT_NOT_APPLIED = "IMPORT_NOT_APPLIED";
    public static final String IMPORT_OVERRODE_DRAFT = "IMPORT_OVERRODE_DRAFT";
    public static final String SERVICES_MERGED = "SERVICES_MERGED";
    public static final String SUBSTITUTION_UNRESOLVED = "SUBSTITUTION_UNRESOLVED";
    public static final String ENV_FILE_NOT_IN_REPO = "ENV_FILE_NOT_IN_REPO";

    public static final String INVALID_ENV_NAME = "INVALID_ENV_NAME";
    public static final String RESERVED_ENV_DROPPED = "RESERVED_ENV_DROPPED";
    public static final String SECRET_VALUE_DROPPED = "SECRET_VALUE_DROPPED";
    public static final String SECRET_TO_PROVISION = "SECRET_TO_PROVISION";

    public static final String UNSUPPORTED_SETTING = "UNSUPPORTED_SETTING";
    public static final String FILE_SECRET_UNSUPPORTED = "FILE_SECRET_UNSUPPORTED";
    public static final String SIDECAR_RESOURCES_UNSUPPORTED = "SIDECAR_RESOURCES_UNSUPPORTED";
    public static final String BUILD_ARG_UNSUPPORTED = "BUILD_ARG_UNSUPPORTED";
    public static final String BUILD_TARGET_UNSUPPORTED = "BUILD_TARGET_UNSUPPORTED";
    public static final String BUILDPACKS_NEEDS_DOCKERFILE = "BUILDPACKS_NEEDS_DOCKERFILE";

    public static final String DOCKERFILE_NO_FROM = "DOCKERFILE_NO_FROM";
    public static final String DOCKERFILE_EXTERNAL_ARTIFACT = "DOCKERFILE_EXTERNAL_ARTIFACT";
    public static final String DOCKERFILE_LOCALHOST_BIND = "DOCKERFILE_LOCALHOST_BIND";
    public static final String DOCKERFILE_FIXED_PORT = "DOCKERFILE_FIXED_PORT";
    public static final String DOCKERFILE_SECRET_IN_IMAGE = "DOCKERFILE_SECRET_IN_IMAGE";
    public static final String DOCKERFILE_ROOT_USER = "DOCKERFILE_ROOT_USER";
    public static final String DOCKERFILE_NO_DOCKERIGNORE = "DOCKERFILE_NO_DOCKERIGNORE";
    public static final String DOCKERFILE_EXPOSE_MISMATCH = "DOCKERFILE_EXPOSE_MISMATCH";
    public static final String DOCKERFILE_UNRESOLVED = "DOCKERFILE_UNRESOLVED";

    public static final String FILE_EXISTS_NOT_OVERWRITTEN = "FILE_EXISTS_NOT_OVERWRITTEN";
    public static final String TOO_MANY_COMPONENTS = "TOO_MANY_COMPONENTS";

    /** An env value's hostname contains another declared service's name — a heuristic guess, not a confirmed match. */
    public static final String CROSS_SERVICE_URL_GUESSED = "CROSS_SERVICE_URL_GUESSED";
    /** Which sidecar an nginx-served frontend's reverse proxy was wired to, when more than one existed to choose from. */
    public static final String REVERSE_PROXY_BACKEND_CHOSEN = "REVERSE_PROXY_BACKEND_CHOSEN";
    /** A merged service had --allow-unauthenticated and is no longer independently, publicly reachable. */
    public static final String INDEPENDENT_SERVICE_NO_LONGER_PUBLIC = "INDEPENDENT_SERVICE_NO_LONGER_PUBLIC";
    /** A merged service's own min/max-instances were discarded in favor of the primary service's. */
    public static final String SCALING_POLICY_DISCARDED = "SCALING_POLICY_DISCARDED";
    /** A dependency on a database/cache/queue/storage client the platform does not provision automatically. */
    public static final String EXTERNAL_INFRASTRUCTURE_DETECTED = "EXTERNAL_INFRASTRUCTURE_DETECTED";
    /** How a declared secret's value actually gets provisioned — restates the platform's real mechanism. */
    public static final String SECRET_PROVISIONING_INSTRUCTIONS = "SECRET_PROVISIONING_INSTRUCTIONS";
    /** The model marked something secret whose name doesn't look credential-shaped — flagged, never reclassified. */
    public static final String SECRET_CLASSIFICATION_UNCERTAIN = "SECRET_CLASSIFICATION_UNCERTAIN";
}
