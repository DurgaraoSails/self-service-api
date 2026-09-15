package com.sails.ai.selfserviceapi.onboarding.generate.stack;

import java.util.List;
import java.util.Map;

/**
 * What {@link StackDetector} found in one directory.
 *
 * @param kind          the recognized stack, or {@link StackKind#UNKNOWN}.
 * @param directory     the directory this was detected in, root as {@code ""}.
 * @param params        template placeholder values — see {@code DockerfileTemplates}, e.g.
 *                      {@code nodeVersion}, {@code distDir}, {@code appModule}.
 * @param evidencePaths the file(s) that led to this detection, for the "reason" a generated
 *                      Dockerfile reports.
 */
public record DetectedStack(StackKind kind, String directory, Map<String, String> params, List<String> evidencePaths) {

    public static DetectedStack unknown(String directory) {
        return new DetectedStack(StackKind.UNKNOWN, directory, Map.of(), List.of());
    }

    public String param(String key, String fallback) {
        String value = params.get(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
