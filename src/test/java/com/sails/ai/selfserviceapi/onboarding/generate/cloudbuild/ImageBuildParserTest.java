package com.sails.ai.selfserviceapi.onboarding.generate.cloudbuild;

import static org.assertj.core.api.Assertions.assertThat;

import com.sails.ai.selfserviceapi.onboarding.generate.GenerationNoticeCode;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ImageBuildParserTest {

    private final ImageBuildParser parser = new ImageBuildParser();

    @Test
    void parsesAPlainDockerBuild() {
        Optional<ImageBuildParser.Result> result = parser.parseDockerBuild(
                List.of("docker", "build", "-t", "gcr.io/proj/app", "."), null);

        assertThat(result).isPresent();
        ImportedBuild build = result.get().build();
        assertThat(build.dockerfile()).isEqualTo("Dockerfile");
        assertThat(build.context()).isEqualTo(".");
        assertThat(build.imageRefs()).containsExactly("gcr.io/proj/app");
    }

    @Test
    void parsesDockerBuildxBuild() {
        Optional<ImageBuildParser.Result> result = parser.parseDockerBuild(
                List.of("docker", "buildx", "build", "-t", "app", "."), null);

        assertThat(result).isPresent();
    }

    @Test
    void readsAnExplicitDockerfileAndContext() {
        Optional<ImageBuildParser.Result> result = parser.parseDockerBuild(
                List.of("docker", "build", "-f", "apps/web/Dockerfile", "-t", "web", "apps/web"), null);

        ImportedBuild build = result.get().build();
        assertThat(build.dockerfile()).isEqualTo("apps/web/Dockerfile");
        assertThat(build.context()).isEqualTo("apps/web");
    }

    @Test
    void prefixesAStepsDirOntoRelativePaths() {
        Optional<ImageBuildParser.Result> result = parser.parseDockerBuild(
                List.of("docker", "build", "-t", "app", "."), "apps/api");

        ImportedBuild build = result.get().build();
        assertThat(build.context()).isEqualTo("apps/api");
        assertThat(build.dockerfile()).isEqualTo("apps/api/Dockerfile");
    }

    @Test
    void collectsMultipleTags() {
        Optional<ImageBuildParser.Result> result = parser.parseDockerBuild(
                List.of("docker", "build", "-t", "app:latest", "-t", "app:1.0", "."), null);

        assertThat(result.get().build().imageRefs()).containsExactly("app:latest", "app:1.0");
    }

    @Test
    void flagsBuildArgNamesAsUnsupportedWithoutKeepingValues() {
        Optional<ImageBuildParser.Result> result = parser.parseDockerBuild(
                List.of("docker", "build", "--build-arg", "SECRET=verylongvalue1234567890", "-t", "app", "."), null);

        assertThat(result.get().build().buildArgNames()).containsExactly("SECRET");
        assertThat(result.get().notices()).anySatisfy(n -> assertThat(n.code()).isEqualTo(GenerationNoticeCode.BUILD_ARG_UNSUPPORTED));
        String everything = result.get().notices().toString();
        assertThat(everything).doesNotContain("verylongvalue1234567890");
    }

    @Test
    void flagsATargetAsUnsupported() {
        Optional<ImageBuildParser.Result> result = parser.parseDockerBuild(
                List.of("docker", "build", "--target", "runtime", "-t", "app", "."), null);

        assertThat(result.get().build().target()).isEqualTo("runtime");
        assertThat(result.get().notices()).anySatisfy(n -> assertThat(n.code()).isEqualTo(GenerationNoticeCode.BUILD_TARGET_UNSUPPORTED));
    }

    @Test
    void isEmptyForANonDockerBuildCommand() {
        assertThat(parser.parseDockerBuild(List.of("docker", "push", "app"), null)).isEmpty();
        assertThat(parser.parseDockerBuild(List.of("gcloud", "run", "deploy"), null)).isEmpty();
    }

    @Test
    void parsesKanikoFlags() {
        Optional<ImageBuildParser.Result> result = parser.parseKaniko(
                List.of("--dockerfile=apps/api/Dockerfile", "--context=dir:///workspace/apps/api",
                        "--destination=gcr.io/proj/api"), null);

        ImportedBuild build = result.get().build();
        assertThat(build.dockerfile()).isEqualTo("apps/api/Dockerfile");
        assertThat(build.context()).isEqualTo("/workspace/apps/api");
        assertThat(build.imageRefs()).containsExactly("gcr.io/proj/api");
    }

    @Test
    void kanikoDefaultsDockerfileToContextSlashDockerfile() {
        Optional<ImageBuildParser.Result> result = parser.parseKaniko(
                List.of("--context=dir://.", "--destination=app"), null);

        assertThat(result.get().build().dockerfile()).isEqualTo("Dockerfile");
    }
}
