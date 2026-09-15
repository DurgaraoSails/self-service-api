package com.sails.ai.selfserviceapi.onboarding.generate.dockerfile;

import static org.assertj.core.api.Assertions.assertThat;

import com.sails.ai.selfserviceapi.onboarding.generate.GenerationNoticeCode;
import java.util.List;
import org.junit.jupiter.api.Test;

class DockerfileLinterTest {

    private final DockerfileLinter linter = new DockerfileLinter();

    @Test
    void aCleanDockerfileHasNoFindings() {
        String dockerfile = """
                FROM node:22-slim AS build
                WORKDIR /app
                COPY package.json package-lock.json ./
                RUN npm ci
                COPY . .
                RUN npm run build

                FROM node:22-slim
                WORKDIR /app
                COPY --from=build /app ./
                USER node
                ENV PORT=8080
                EXPOSE 8080
                CMD ["sh", "-c", "node server.js"]
                """;

        assertThat(linter.lint(dockerfile, 8080)).isEmpty();
    }

    @Test
    void flagsAMissingFromInstruction() {
        List<LintFinding> findings = linter.lint("RUN echo hi\n", 8080);

        assertThat(findings).singleElement()
                .satisfies(f -> assertThat(f.code()).isEqualTo(GenerationNoticeCode.DOCKERFILE_NO_FROM));
    }

    /** The classic "built outside docker build" shape: a cloudbuild.yaml mvn step, then a bare COPY of the jar. */
    @Test
    void flagsAnExternalArtifactCopiedWithNoBuildStepToProduceIt() {
        String dockerfile = """
                FROM eclipse-temurin:21-jre
                COPY target/app.jar /app/app.jar
                USER app
                ENTRYPOINT ["java", "-jar", "/app/app.jar"]
                """;

        List<LintFinding> findings = linter.lint(dockerfile, 8080);

        assertThat(findings).anySatisfy(f -> {
            assertThat(f.code()).isEqualTo(GenerationNoticeCode.DOCKERFILE_EXTERNAL_ARTIFACT);
            assertThat(f.severity()).isEqualTo(LintFinding.Severity.ERROR);
        });
    }

    @Test
    void doesNotFlagAMultiStageCopyOfABuildOutput() {
        String dockerfile = """
                FROM maven:3.9-eclipse-temurin-21 AS build
                COPY . .
                RUN mvn -B package

                FROM eclipse-temurin:21-jre
                COPY --from=build /app/target/app.jar /app/app.jar
                USER app
                ENTRYPOINT ["java", "-jar", "/app/app.jar"]
                """;

        assertThat(linter.lint(dockerfile, 8080)).noneSatisfy(f ->
                assertThat(f.code()).isEqualTo(GenerationNoticeCode.DOCKERFILE_EXTERNAL_ARTIFACT));
    }

    @Test
    void flagsALoopbackOnlyBind() {
        String dockerfile = """
                FROM node:22-slim
                USER node
                CMD ["node", "-e", "app.listen(8080, '127.0.0.1')"]
                """;

        List<LintFinding> findings = linter.lint(dockerfile, 8080);

        assertThat(findings).anySatisfy(f -> assertThat(f.code()).isEqualTo(GenerationNoticeCode.DOCKERFILE_LOCALHOST_BIND));
    }

    @Test
    void flagsAFixedPortThatDoesNotMatchTheContainerPort() {
        String dockerfile = """
                FROM node:22-slim
                USER node
                CMD ["node", "server.js", "--port=3000"]
                """;

        List<LintFinding> findings = linter.lint(dockerfile, 8080);

        assertThat(findings).anySatisfy(f -> {
            assertThat(f.code()).isEqualTo(GenerationNoticeCode.DOCKERFILE_FIXED_PORT);
            assertThat(f.severity()).isEqualTo(LintFinding.Severity.WARNING);
        });
    }

    @Test
    void doesNotFlagAFixedPortThatMatchesOrThatReferencesThePortVariable() {
        String matching = """
                FROM node:22-slim
                USER node
                CMD ["sh", "-c", "node server.js --port=8080"]
                """;
        assertThat(linter.lint(matching, 8080)).noneSatisfy(f ->
                assertThat(f.code()).isEqualTo(GenerationNoticeCode.DOCKERFILE_FIXED_PORT));

        String usingVariable = """
                FROM node:22-slim
                USER node
                CMD ["sh", "-c", "node server.js --port=${PORT}"]
                """;
        assertThat(linter.lint(usingVariable, 8080)).noneSatisfy(f ->
                assertThat(f.code()).isEqualTo(GenerationNoticeCode.DOCKERFILE_FIXED_PORT));
    }

    @Test
    void flagsASecretShapedEnvWithALiteralValue() {
        String dockerfile = """
                FROM node:22-slim
                ENV DB_PASSWORD=hunter2verylongvalue1234567890
                USER node
                CMD ["node", "server.js"]
                """;

        List<LintFinding> findings = linter.lint(dockerfile, 8080);

        assertThat(findings).anySatisfy(f -> assertThat(f.code()).isEqualTo(GenerationNoticeCode.DOCKERFILE_SECRET_IN_IMAGE));
    }

    @Test
    void flagsACopyOfACredentialShapedFile() {
        String dockerfile = """
                FROM node:22-slim
                COPY .env .env
                USER node
                CMD ["node", "server.js"]
                """;

        List<LintFinding> findings = linter.lint(dockerfile, 8080);

        assertThat(findings).anySatisfy(f -> assertThat(f.code()).isEqualTo(GenerationNoticeCode.DOCKERFILE_SECRET_IN_IMAGE));
    }

    @Test
    void flagsTheFinalStageRunningAsRootWithNoUser() {
        String dockerfile = """
                FROM node:22-slim
                CMD ["node", "server.js"]
                """;

        List<LintFinding> findings = linter.lint(dockerfile, 8080);

        assertThat(findings).anySatisfy(f -> {
            assertThat(f.code()).isEqualTo(GenerationNoticeCode.DOCKERFILE_ROOT_USER);
            assertThat(f.severity()).isEqualTo(LintFinding.Severity.INFO);
        });
    }

    @Test
    void doesNotFlagAKnownNonRootBaseImageWithNoExplicitUser() {
        String dockerfile = """
                FROM gcr.io/distroless/static-debian12:nonroot
                ENTRYPOINT ["/server"]
                """;

        assertThat(linter.lint(dockerfile, 8080)).noneSatisfy(f ->
                assertThat(f.code()).isEqualTo(GenerationNoticeCode.DOCKERFILE_ROOT_USER));
    }

    @Test
    void flagsAnExposeThatDoesNotMatchTheContainerPort() {
        String dockerfile = """
                FROM node:22-slim
                USER node
                EXPOSE 3000
                CMD ["node", "server.js"]
                """;

        List<LintFinding> findings = linter.lint(dockerfile, 8080);

        assertThat(findings).anySatisfy(f -> {
            assertThat(f.code()).isEqualTo(GenerationNoticeCode.DOCKERFILE_EXPOSE_MISMATCH);
            assertThat(f.severity()).isEqualTo(LintFinding.Severity.INFO);
        });
    }

    /** A RUN split across several lines with backslash continuations must still parse as one instruction. */
    @Test
    void handlesLineContinuationsInARunInstruction() {
        String dockerfile = """
                FROM node:22-slim
                RUN apt-get update && \\
                    apt-get install -y curl && \\
                    rm -rf /var/lib/apt/lists/*
                USER node
                CMD ["node", "server.js"]
                """;

        // No exception, and the continued RUN doesn't itself trip anything.
        assertThat(linter.lint(dockerfile, 8080)).isEmpty();
    }

    /** FROM ... AS build/runtime stages must be tracked correctly so "final stage" checks target the right one. */
    @Test
    void tracksMultipleNamedStagesCorrectly() {
        String dockerfile = """
                FROM node:22-slim AS deps
                RUN npm ci
                FROM node:22-slim AS build
                COPY --from=deps /app/node_modules ./node_modules
                RUN npm run build
                FROM node:22-slim
                COPY --from=build /app/dist ./dist
                USER node
                CMD ["node", "server.js"]
                """;

        assertThat(linter.lint(dockerfile, 8080)).isEmpty();
    }
}
