package com.sails.ai.selfserviceapi.deploypipeline.manifest;

/**
 * A POC's declared {@code platform.database}/{@code platform.files} opt-in. Purely informational
 * today — self-service-api grants file management to any POC-scoped token holder regardless of
 * this flag (see {@code PocFilesController}), and no per-POC database provisioning exists yet.
 * Parsed and preserved (instead of silently dropped, as {@link ManifestParser} did before this
 * type existed) so a manifest author's declaration survives into the stored manifest for when
 * either capability becomes conditional on it.
 */
public record PlatformConfig(boolean databaseEnabled, boolean filesEnabled) {

    public static PlatformConfig none() {
        return new PlatformConfig(false, false);
    }
}
