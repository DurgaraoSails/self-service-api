package com.sails.ai.selfserviceapi.onboarding.generate.cloudbuild;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sails.ai.selfserviceapi.onboarding.generate.cloudbuild.CloudBuildConfigParser.Config;
import com.sails.ai.selfserviceapi.onboarding.generate.cloudbuild.CloudBuildConfigParser.Step;
import org.junit.jupiter.api.Test;

class CloudBuildConfigParserTest {

    private final CloudBuildConfigParser parser = new CloudBuildConfigParser();

    @Test
    void parsesStepsWithArgs() {
        Config config = parser.parse("""
                steps:
                  - name: 'gcr.io/cloud-builders/docker'
                    args: ['build', '-t', 'gcr.io/proj/app', '.']
                    id: 'build'
                """);

        assertThat(config.steps()).hasSize(1);
        Step step = config.steps().get(0);
        assertThat(step.name()).isEqualTo("gcr.io/cloud-builders/docker");
        assertThat(step.args()).containsExactly("build", "-t", "gcr.io/proj/app", ".");
        assertThat(step.id()).isEqualTo("build");
    }

    @Test
    void parsesAScriptStep() {
        Config config = parser.parse("""
                steps:
                  - name: 'gcr.io/cloud-builders/docker'
                    script: |
                      docker build -t app .
                      docker push app
                """);

        assertThat(config.steps().get(0).script()).contains("docker build -t app .");
    }

    @Test
    void parsesStepDirAndEnv() {
        Config config = parser.parse("""
                steps:
                  - name: 'node'
                    dir: 'apps/web'
                    env: ['NODE_ENV=production']
                    args: ['run', 'build']
                """);

        Step step = config.steps().get(0);
        assertThat(step.dir()).isEqualTo("apps/web");
        assertThat(step.env()).containsEntry("NODE_ENV", "production");
    }

    @Test
    void resolvesUserDefinedSubstitutionsEverywhereTheyAppear() {
        Config config = parser.parse("""
                substitutions:
                  _REGION: us-central1
                steps:
                  - name: 'gcloud'
                    args: ['run', 'deploy', 'svc', '--region=$_REGION']
                """);

        assertThat(config.steps().get(0).args()).contains("--region=us-central1");
    }

    @Test
    void resolvesSubstitutionsThatReferenceOtherSubstitutions() {
        Config config = parser.parse("""
                substitutions:
                  _BASE: myapp
                  _IMAGE: gcr.io/proj/$_BASE
                steps:
                  - name: 'docker'
                    args: ['build', '-t', '$_IMAGE', '.']
                """);

        assertThat(config.steps().get(0).args()).contains("gcr.io/proj/myapp");
    }

    @Test
    void leavesBuiltInSubstitutionsUnresolved() {
        Config config = parser.parse("""
                steps:
                  - name: 'docker'
                    args: ['build', '-t', 'gcr.io/$PROJECT_ID/app:$SHORT_SHA', '.']
                """);

        assertThat(config.steps().get(0).args()).contains("gcr.io/$PROJECT_ID/app:$SHORT_SHA");
    }

    @Test
    void treatsDoubleDollarAsALiteralDollarSign() {
        Config config = parser.parse("""
                steps:
                  - name: 'bash'
                    args: ['-c', 'echo $$HOME']
                """);

        assertThat(config.steps().get(0).args()).contains("echo $HOME");
    }

    @Test
    void parsesOptionsEnvAndTopLevelImages() {
        Config config = parser.parse("""
                steps:
                  - name: 'docker'
                    args: ['build', '.']
                options:
                  env:
                    - 'DOCKER_BUILDKIT=1'
                images:
                  - 'gcr.io/$PROJECT_ID/app'
                substitutions: {}
                """);

        assertThat(config.optionsEnv()).containsEntry("DOCKER_BUILDKIT", "1");
        assertThat(config.images()).containsExactly("gcr.io/$PROJECT_ID/app");
    }

    @Test
    void rejectsInvalidYamlWithoutThrowingAnUncheckedParserError() {
        assertThatThrownBy(() -> parser.parse("steps: [this is not: valid: yaml: at all"))
                .isInstanceOf(CloudBuildParseException.class);
    }

    @Test
    void rejectsAFileWithNoStepsKey() {
        assertThatThrownBy(() -> parser.parse("substitutions:\n  _X: y\n"))
                .isInstanceOf(CloudBuildParseException.class);
    }

    @Test
    void jsonParsesAsYamlWithNoSpecialCasing() {
        Config config = parser.parse("""
                {"steps": [{"name": "docker", "args": ["build", "-t", "app", "."]}]}
                """);

        assertThat(config.steps()).hasSize(1);
        assertThat(config.steps().get(0).args()).containsExactly("build", "-t", "app", ".");
    }
}
