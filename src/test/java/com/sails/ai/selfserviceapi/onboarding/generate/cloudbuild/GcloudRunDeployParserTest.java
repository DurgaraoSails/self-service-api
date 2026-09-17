package com.sails.ai.selfserviceapi.onboarding.generate.cloudbuild;

import static org.assertj.core.api.Assertions.assertThat;

import com.sails.ai.selfserviceapi.onboarding.generate.GenerationNoticeCode;
import com.sails.ai.selfserviceapi.onboarding.generate.cloudbuild.GcloudRunDeployParser.RawContainer;
import com.sails.ai.selfserviceapi.onboarding.generate.cloudbuild.GcloudRunDeployParser.RawDeploy;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GcloudRunDeployParserTest {

    private final GcloudRunDeployParser parser = new GcloudRunDeployParser();

    private List<String> words(String commandLine) {
        return List.of(commandLine.split("\\s+"));
    }

    @Test
    void parsesTheServiceNameImageAndPort() {
        Optional<RawDeploy> result = parser.parse(words(
                "gcloud run deploy myapp --image=gcr.io/proj/app --port=7000 --region=us-central1"));

        assertThat(result).isPresent();
        RawDeploy deploy = result.get();
        assertThat(deploy.serviceName()).isEqualTo("myapp");
        assertThat(deploy.containers()).hasSize(1);
        RawContainer container = deploy.containers().get(0);
        assertThat(container.imageRef()).isEqualTo("gcr.io/proj/app");
        assertThat(container.port()).isEqualTo(7000);
    }

    @Test
    void supportsTheBetaCommandPrefix() {
        Optional<RawDeploy> result = parser.parse(words("gcloud beta run deploy myapp --image=app"));

        assertThat(result).isPresent();
    }

    @Test
    void isEmptyForANonDeployCommand() {
        assertThat(parser.parse(words("gcloud run services list"))).isEmpty();
        assertThat(parser.parse(words("docker build -t app ."))).isEmpty();
    }

    @Test
    void parsesCpuMemoryAndInstanceScaling() {
        Optional<RawDeploy> result = parser.parse(words(
                "gcloud run deploy myapp --image=app --cpu=2 --memory=1Gi --min-instances=1 --max-instances=5"));

        RawDeploy deploy = result.get();
        assertThat(deploy.containers().get(0).cpu()).isEqualTo("2");
        assertThat(deploy.containers().get(0).memory()).isEqualTo("1Gi");
        assertThat(deploy.minInstances()).isEqualTo(1);
        assertThat(deploy.maxInstances()).isEqualTo(5);
    }

    @Test
    void parsesCommaSeparatedEnvVars() {
        Optional<RawDeploy> result = parser.parse(words(
                "gcloud run deploy myapp --image=app --set-env-vars=LOG_LEVEL=info,REGION=us-central1"));

        assertThat(result.get().containers().get(0).rawEnv())
                .containsEntry("LOG_LEVEL", "info").containsEntry("REGION", "us-central1");
    }

    @Test
    void parsesTheAlternateDelimiterEnvVarsForm() {
        Optional<RawDeploy> result = parser.parse(List.of(
                "gcloud", "run", "deploy", "myapp", "--image=app",
                "--set-env-vars=^;^DATABASE_URL=postgres://user:pass@host/db;LOG_LEVEL=info"));

        assertThat(result.get().containers().get(0).rawEnv())
                .containsEntry("DATABASE_URL", "postgres://user:pass@host/db")
                .containsEntry("LOG_LEVEL", "info");
    }

    @Test
    void mergesRepeatedEnvVarFlags() {
        Optional<RawDeploy> result = parser.parse(words(
                "gcloud run deploy myapp --image=app --set-env-vars=A=1 --update-env-vars=B=2"));

        assertThat(result.get().containers().get(0).rawEnv()).containsEntry("A", "1").containsEntry("B", "2");
    }

    @Test
    void collectsSecretNamesWithoutKeepingTheSecretManagerReference() {
        Optional<RawDeploy> result = parser.parse(words(
                "gcloud run deploy myapp --image=app --set-secrets=DB_PASSWORD=my-secret-ref:latest"));

        RawContainer container = result.get().containers().get(0);
        assertThat(container.secretEnvNames()).containsExactly("DB_PASSWORD");
        assertThat(container.toString()).doesNotContain("my-secret-ref");
    }

    @Test
    void flagsAFileMountedSecretAsUnsupported() {
        Optional<RawDeploy> result = parser.parse(words(
                "gcloud run deploy myapp --image=app --set-secrets=/etc/secret/key=my-secret:latest"));

        RawContainer container = result.get().containers().get(0);
        assertThat(container.secretEnvNames()).isEmpty();
        assertThat(container.notices()).anySatisfy(n -> assertThat(n.code()).isEqualTo(GenerationNoticeCode.FILE_SECRET_UNSUPPORTED));
    }

    @Test
    void theStartupProbeWinsOverTheLivenessProbe() {
        Optional<RawDeploy> result = parser.parse(words(
                "gcloud run deploy myapp --image=app "
                        + "--startup-probe=httpGet.path=/startupz,httpGet.port=8080 "
                        + "--liveness-probe=httpGet.path=/livez,httpGet.port=8080"));

        assertThat(result.get().containers().get(0).health()).isEqualTo("/startupz");
    }

    @Test
    void usesTheLivenessProbeWhenNoStartupProbeIsSet() {
        Optional<RawDeploy> result = parser.parse(words(
                "gcloud run deploy myapp --image=app --liveness-probe=httpGet.path=/livez,httpGet.port=8080"));

        assertThat(result.get().containers().get(0).health()).isEqualTo("/livez");
    }

    @Test
    void splitsMultipleContainerSegments() {
        Optional<RawDeploy> result = parser.parse(words(
                "gcloud run deploy myapp "
                        + "--container=web --image=web-img --port=8080 "
                        + "--container=api --image=api-img --port=7000"));

        RawDeploy deploy = result.get();
        assertThat(deploy.containers()).hasSize(2);
        assertThat(deploy.containers().get(0).name()).isEqualTo("web");
        assertThat(deploy.containers().get(0).port()).isEqualTo(8080);
        assertThat(deploy.containers().get(1).name()).isEqualTo("api");
        assertThat(deploy.containers().get(1).port()).isEqualTo(7000);
    }

    @Test
    void serviceLevelFlagsApplyRegardlessOfWhichSegmentTheyAppearIn() {
        Optional<RawDeploy> result = parser.parse(words(
                "gcloud run deploy myapp --container=web --image=web-img --min-instances=2 "
                        + "--container=api --image=api-img"));

        assertThat(result.get().minInstances()).isEqualTo(2);
    }

    @Test
    void sanitizesTheServiceNameToTheAllowedShape() {
        Optional<RawDeploy> result = parser.parse(words("gcloud run deploy My_App123 --image=app"));

        assertThat(result.get().serviceName()).matches("^[a-z0-9]+(-[a-z0-9]+)*$");
    }

    @Test
    void reportsAnUnsupportedSettingWithoutLeakingASecretShapedValue() {
        Optional<RawDeploy> result = parser.parse(words(
                "gcloud run deploy myapp --image=app --service-account=ghp_abcdefghijklmnopqrstuvwxyz0123456789"));

        assertThat(result.get().notices()).anySatisfy(n -> {
            assertThat(n.code()).isEqualTo(GenerationNoticeCode.UNSUPPORTED_SETTING);
            assertThat(n.message()).doesNotContain("ghp_abcdefghijklmnopqrstuvwxyz0123456789");
        });
    }

    @Test
    void ignoresPlatformManagedFlagsWithoutAnyNotice() {
        Optional<RawDeploy> result = parser.parse(words(
                "gcloud run deploy myapp --image=app --region=us-central1 --project=my-proj "
                        + "--platform=managed --allow-unauthenticated --quiet"));

        assertThat(result.get().notices()).isEmpty();
    }

    @Test
    void parsesASourceDeployWithNoImage() {
        Optional<RawDeploy> result = parser.parse(words("gcloud run deploy myapp --source=apps/web --port=3000"));

        RawContainer container = result.get().containers().get(0);
        assertThat(container.sourceDir()).isEqualTo("apps/web");
        assertThat(container.imageRef()).isNull();
    }
}
