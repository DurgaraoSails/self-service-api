package com.sails.ai.selfserviceapi.deploypipeline.manifest;

/** Parsed so a manifest declaring these validates cleanly; not acted on until Phase 4 exists. */
public record Platform(boolean databaseEnabled, boolean filesEnabled) {

    public static Platform none() {
        return new Platform(false, false);
    }
}
