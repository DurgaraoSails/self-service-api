package com.sails.ai.selfserviceapi.onboarding.generate.stack;

/**
 * Every application stack {@code StackDetector} recognizes, each backed by a template at
 * {@code src/main/resources/templates/dockerfiles/<name-lowercase-with-dashes>.Dockerfile.tmpl}.
 * {@code UNKNOWN} means no marker matched, or two mutually exclusive ecosystems' markers were both
 * present in the same directory — either way, {@code DockerfilePlanner} falls back to the model.
 */
public enum StackKind {
    ANGULAR_SPA,
    VITE_SPA,
    CRA_SPA,
    NEXTJS,
    NODE_SERVER,
    STATIC_SITE,
    JAVA_MAVEN,
    JAVA_GRADLE,
    PYTHON_FASTAPI,
    PYTHON_FLASK,
    PYTHON_DJANGO,
    PYTHON_STREAMLIT,
    PYTHON_GRADIO,
    GO,
    DOTNET,
    UNKNOWN;

    /**
     * True for a stack that serves a browser frontend directly — a SPA build, a plain static site,
     * or Next.js's own server. Used to prefer this container as ingress over a pure backend API when
     * a cloudbuild.yaml (or the model's own reasoning) leaves more than one plausible candidate, and
     * to decide whether an nginx-served frontend needs a reverse proxy to a backend sidecar.
     *
     * <p>{@code NODE_SERVER} is deliberately excluded — it is a plain Node process that could just as
     * easily be a backend API as a frontend, and {@link StackDetector} already only assigns it when
     * no more specific frontend stack matched, so guessing it as browser-facing here would be wrong
     * as often as it was right. That genuine ambiguity is left to the caller.
     */
    public boolean isBrowserFacing() {
        return this == ANGULAR_SPA || this == VITE_SPA || this == CRA_SPA || this == NEXTJS || this == STATIC_SITE;
    }

    /** True for a stack that is, on its own, never a browser-facing frontend — a pure backend/API/worker. */
    public boolean isBackendOnly() {
        return this == JAVA_MAVEN || this == JAVA_GRADLE || this == PYTHON_FASTAPI || this == PYTHON_FLASK
                || this == PYTHON_DJANGO || this == PYTHON_STREAMLIT || this == PYTHON_GRADIO || this == GO
                || this == DOTNET;
    }
}
