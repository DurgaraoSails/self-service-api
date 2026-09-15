package com.sails.ai.selfserviceapi.onboarding.generate.stack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubRepoRef;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubService;
import com.sails.ai.selfserviceapi.onboarding.generate.RepoFileReader;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StackDetectorTest {

    private static final GitHubRepoRef REPO = new GitHubRepoRef("acme", "contract-agent");
    private static final String SHA = "abc123";

    private final GitHubService gitHubService = mock(GitHubService.class);
    private final RepoFileReader fileReader = new RepoFileReader(gitHubService, REPO, SHA);
    private final StackDetector detector = new StackDetector();

    private void stub(String path, String content) {
        when(gitHubService.getFileContent(REPO, SHA, path)).thenReturn(Optional.of(content));
    }

    @Test
    void detectsNextjs() {
        stub("package.json", """
                {"dependencies": {"next": "14.0.0", "react": "18.0.0"}}
                """);

        DetectedStack stack = detector.detect(fileReader, "", Set.of("package.json"));

        assertThat(stack.kind()).isEqualTo(StackKind.NEXTJS);
    }

    @Test
    void detectsAngularSpaAndReadsItsOutputPath() {
        stub("package.json", """
                {"dependencies": {"@angular/core": "17.0.0"}, "engines": {"node": "20"}}
                """);
        stub("angular.json", """
                {"projects": {"web": {"architect": {"build": {
                    "builder": "@angular/build:application",
                    "options": {"outputPath": "dist/web"}
                }}}}}
                """);

        DetectedStack stack = detector.detect(fileReader, "", Set.of("package.json", "angular.json"));

        assertThat(stack.kind()).isEqualTo(StackKind.ANGULAR_SPA);
        assertThat(stack.param("nodeVersion", null)).isEqualTo("20");
        assertThat(stack.param("distDir", null)).isEqualTo("dist/web/browser");
    }

    @Test
    void detectsViteSpaButNotWhenAServerFrameworkIsAlsoPresent() {
        stub("package.json", """
                {"devDependencies": {"vite": "5.0.0"}}
                """);
        DetectedStack pureVite = detector.detect(fileReader, "", Set.of("package.json"));
        assertThat(pureVite.kind()).isEqualTo(StackKind.VITE_SPA);
        assertThat(pureVite.param("distDir", null)).isEqualTo("dist");

        stub("apps/mixed/package.json", """
                {"devDependencies": {"vite": "5.0.0"}, "dependencies": {"express": "4.0.0"}}
                """);
        DetectedStack mixed = detector.detect(fileReader, "apps/mixed", Set.of("package.json"));
        assertThat(mixed.kind()).isEqualTo(StackKind.NODE_SERVER);
    }

    @Test
    void detectsCraSpa() {
        stub("package.json", """
                {"dependencies": {"react-scripts": "5.0.0"}}
                """);

        DetectedStack stack = detector.detect(fileReader, "", Set.of("package.json"));

        assertThat(stack.kind()).isEqualTo(StackKind.CRA_SPA);
        assertThat(stack.param("distDir", null)).isEqualTo("build");
    }

    @Test
    void detectsNodeServerFromAMainEntryWithNoRecognizedFramework() {
        stub("package.json", """
                {"main": "server.js", "scripts": {"build": "tsc"}}
                """);

        DetectedStack stack = detector.detect(fileReader, "", Set.of("package.json"));

        assertThat(stack.kind()).isEqualTo(StackKind.NODE_SERVER);
        assertThat(stack.param("startCmd", null)).isEqualTo("node server.js");
        assertThat(stack.param("hasBuild", null)).isEqualTo("true");
    }

    @Test
    void detectsStaticSiteWhenThereIsNoPackageJsonAtAll() {
        DetectedStack stack = detector.detect(fileReader, "", Set.of("index.html", "styles.css"));

        assertThat(stack.kind()).isEqualTo(StackKind.STATIC_SITE);
    }

    @Test
    void detectsFastapiAndFindsItsAppModule() {
        stub("requirements.txt", "fastapi\nuvicorn\n");
        stub("main.py", "from fastapi import FastAPI\napp = FastAPI()\n");

        DetectedStack stack = detector.detect(fileReader, "", Set.of("requirements.txt", "main.py"));

        assertThat(stack.kind()).isEqualTo(StackKind.PYTHON_FASTAPI);
        assertThat(stack.param("appModule", null)).isEqualTo("main:app");
        assertThat(stack.param("installCmd", null)).isEqualTo("-r requirements.txt");
    }

    @Test
    void detectsFlask() {
        stub("requirements.txt", "flask\n");
        stub("app.py", "from flask import Flask\napp = Flask(__name__)\n");

        DetectedStack stack = detector.detect(fileReader, "", Set.of("requirements.txt", "app.py"));

        assertThat(stack.kind()).isEqualTo(StackKind.PYTHON_FLASK);
        assertThat(stack.param("appModule", null)).isEqualTo("app:app");
    }

    @Test
    void detectsDjangoOnlyWithManagePyPresent() {
        stub("requirements.txt", "django\n");

        DetectedStack stack = detector.detect(fileReader, "", Set.of("requirements.txt", "manage.py"));

        assertThat(stack.kind()).isEqualTo(StackKind.PYTHON_DJANGO);
    }

    @Test
    void detectsStreamlit() {
        stub("requirements.txt", "streamlit\n");
        stub("main.py", "import streamlit as st\n");

        DetectedStack stack = detector.detect(fileReader, "", Set.of("requirements.txt", "main.py"));

        assertThat(stack.kind()).isEqualTo(StackKind.PYTHON_STREAMLIT);
        assertThat(stack.param("entryScript", null)).isEqualTo("main.py");
    }

    @Test
    void detectsGradio() {
        stub("requirements.txt", "gradio\n");

        DetectedStack stack = detector.detect(fileReader, "", Set.of("requirements.txt"));

        assertThat(stack.kind()).isEqualTo(StackKind.PYTHON_GRADIO);
    }

    @Test
    void streamlitAndGradioOutrankFastapiWhenBothPresent() {
        stub("requirements.txt", "fastapi\nstreamlit\n");

        DetectedStack stack = detector.detect(fileReader, "", Set.of("requirements.txt"));

        assertThat(stack.kind()).isEqualTo(StackKind.PYTHON_STREAMLIT);
    }

    @Test
    void detectsJavaMavenAndSpringBoot() {
        stub("pom.xml", """
                <project><properties><java.version>21</java.version></properties>
                <dependencies><dependency><artifactId>spring-boot-starter-web</artifactId></dependency></dependencies>
                </project>
                """);

        DetectedStack stack = detector.detect(fileReader, "", Set.of("pom.xml", "mvnw"));

        assertThat(stack.kind()).isEqualTo(StackKind.JAVA_MAVEN);
        assertThat(stack.param("javaVersion", null)).isEqualTo("21");
        assertThat(stack.param("buildCmd", null)).isEqualTo("./mvnw -B -DskipTests package");
        assertThat(stack.param("runArgs", null)).contains("--server.port");
    }

    @Test
    void detectsJavaGradle() {
        stub("build.gradle.kts", "languageVersion.set(JavaLanguageVersion.of(17))\n");

        DetectedStack stack = detector.detect(fileReader, "", Set.of("build.gradle.kts"));

        assertThat(stack.kind()).isEqualTo(StackKind.JAVA_GRADLE);
        assertThat(stack.param("javaVersion", null)).isEqualTo("17");
    }

    @Test
    void detectsGoAndItsVersionDirective() {
        stub("go.mod", "module example.com/app\n\ngo 1.22\n");

        DetectedStack stack = detector.detect(fileReader, "", Set.of("go.mod"));

        assertThat(stack.kind()).isEqualTo(StackKind.GO);
        assertThat(stack.param("goVersion", null)).isEqualTo("1.22");
    }

    @Test
    void detectsDotnetFromItsSingleCsproj() {
        stub("Api.csproj", "<Project><PropertyGroup><TargetFramework>net8.0</TargetFramework></PropertyGroup></Project>");

        DetectedStack stack = detector.detect(fileReader, "", Set.of("Api.csproj"));

        assertThat(stack.kind()).isEqualTo(StackKind.DOTNET);
        assertThat(stack.param("dotnetVersion", null)).isEqualTo("8.0");
        assertThat(stack.param("assemblyName", null)).isEqualTo("Api");
    }

    @Test
    void refusesToGuessWhenTwoCsprojFilesArePresent() {
        DetectedStack stack = detector.detect(fileReader, "", Set.of("Api.csproj", "Api.Tests.csproj"));

        assertThat(stack.kind()).isEqualTo(StackKind.UNKNOWN);
    }

    /** Node and Python markers in the same directory is a conflict, not a guess. */
    @Test
    void isUnknownWhenNodeAndPythonMarkersConflict() {
        DetectedStack stack = detector.detect(fileReader, "", Set.of("package.json", "requirements.txt"));

        assertThat(stack.kind()).isEqualTo(StackKind.UNKNOWN);
    }

    @Test
    void isUnknownWithNoMarkersAtAll() {
        DetectedStack stack = detector.detect(fileReader, "", Set.of("README.md"));

        assertThat(stack.kind()).isEqualTo(StackKind.UNKNOWN);
    }

    @Test
    void fallsBackToDefaultsWhenPackageJsonCannotBeRead() {
        DetectedStack stack = detector.detect(fileReader, "", Set.of("package.json"));

        assertThat(stack.kind()).isEqualTo(StackKind.UNKNOWN);
    }
}
