package com.sails.ai.selfserviceapi.deploypipeline.manifest;

/**
 * The result of resolving a POC's manifest for a build: the parsed, validated shape plus the raw
 * YAML exactly as read from GitHub (null when the repo had no poc.yaml at all, in which case
 * {@code manifest} is {@link ManifestService}'s synthesized single-container default).
 *
 * <p>{@code rawYaml} is what gets stored verbatim on the version — see {@code PocVersion.manifestYaml}
 * — so a later redeploy parses exactly what was read at build time, never a fresh GitHub call.
 */
public record ManifestResolution(String rawYaml, PocManifest manifest) {
}
