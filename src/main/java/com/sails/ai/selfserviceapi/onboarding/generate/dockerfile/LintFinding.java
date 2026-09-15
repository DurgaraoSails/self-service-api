package com.sails.ai.selfserviceapi.onboarding.generate.dockerfile;

/**
 * One thing {@link DockerfileLinter} found in a Dockerfile's text. Deliberately its own small type
 * rather than {@code GenerationNotice} directly — linting runs over raw Dockerfile content with no
 * notion of which file/container it belongs to yet, which {@code DockerfilePlanner} (Phase 3)
 * attaches when it turns a finding into a response-facing notice.
 */
public record LintFinding(Severity severity, String code, String message) {

    public enum Severity {
        ERROR, WARNING, INFO
    }

    public static LintFinding error(String code, String message) {
        return new LintFinding(Severity.ERROR, code, message);
    }

    public static LintFinding warning(String code, String message) {
        return new LintFinding(Severity.WARNING, code, message);
    }

    public static LintFinding info(String code, String message) {
        return new LintFinding(Severity.INFO, code, message);
    }
}
