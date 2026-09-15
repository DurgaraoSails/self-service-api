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
    UNKNOWN
}
