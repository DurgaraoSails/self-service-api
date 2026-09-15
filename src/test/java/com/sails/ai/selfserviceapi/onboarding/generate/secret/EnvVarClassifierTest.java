package com.sails.ai.selfserviceapi.onboarding.generate.secret;

import static org.assertj.core.api.Assertions.assertThat;

import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestProperties;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestRequirement;
import com.sails.ai.selfserviceapi.onboarding.generate.GenerationNoticeCode;
import com.sails.ai.selfserviceapi.onboarding.generate.secret.EnvVarClassifier.Result;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EnvVarClassifierTest {

    private final EnvVarClassifier classifier = new EnvVarClassifier(new ManifestProperties(null, null, 0));

    @Test
    void dropsAnInvalidEnvName() {
        Result result = classifier.classify(Map.of("not a valid name", "x"), false, Map.of());

        assertThat(result.env()).isEmpty();
        assertThat(result.notices()).anySatisfy(n -> assertThat(n.code()).isEqualTo(GenerationNoticeCode.INVALID_ENV_NAME));
    }

    @Test
    void claimsPortWhenTheContainerHasNoneYet() {
        Result result = classifier.classify(Map.of("PORT", "7000"), false, Map.of());

        assertThat(result.port()).isEqualTo(7000);
        assertThat(result.env()).doesNotContainKey("PORT");
        assertThat(result.notices()).anySatisfy(n -> assertThat(n.code()).isEqualTo(GenerationNoticeCode.RESERVED_ENV_DROPPED));
    }

    /** An explicitly-declared port always wins over an imported env var — PORT is still dropped, but not claimed. */
    @Test
    void neverOverridesAnExistingPort() {
        Result result = classifier.classify(Map.of("PORT", "7000"), true, Map.of());

        assertThat(result.port()).isNull();
        assertThat(result.env()).doesNotContainKey("PORT");
    }

    @Test
    void dropsReservedNamesAndPrefixes() {
        Result result = classifier.classify(Map.of(
                "POC_SLUG", "x", "PLATFORM_API_URL", "y", "PORTAL_ORIGIN", "z",
                "SAILS_ANYTHING", "a", "SVC_API_URL", "b"), false, Map.of());

        assertThat(result.env()).isEmpty();
        assertThat(result.notices()).hasSize(5)
                .allSatisfy(n -> assertThat(n.code()).isEqualTo(GenerationNoticeCode.RESERVED_ENV_DROPPED));
    }

    @Test
    void secretShapedNameBecomesARequirementNeverAnEnvEntry() {
        Result result = classifier.classify(Map.of("DB_PASSWORD", "hunter2verylongvalue1234567890"), false, Map.of());

        assertThat(result.env()).doesNotContainKey("DB_PASSWORD");
        assertThat(result.requires()).extracting(ManifestRequirement::name).containsExactly("DB_PASSWORD");
        assertThat(result.requires()).extracting(ManifestRequirement::secret).containsExactly(true);
        assertThat(result.notices()).extracting(n -> n.code())
                .contains(GenerationNoticeCode.SECRET_TO_PROVISION, GenerationNoticeCode.SECRET_VALUE_DROPPED);
    }

    @Test
    void secretShapedValueBecomesARequirementEvenWithAnOrdinaryName() {
        Result result = classifier.classify(Map.of("SOME_TOKEN_VALUE", "ghp_abcdefghijklmnopqrstuvwxyz0123456789"),
                false, Map.of());

        assertThat(result.requires()).extracting(ManifestRequirement::name).containsExactly("SOME_TOKEN_VALUE");
    }

    /** The secret value itself must never appear anywhere in the output — the privacy rule. */
    @Test
    void neverLeaksTheSecretValueItself() {
        Result result = classifier.classify(Map.of("DB_PASSWORD", "hunter2verylongvalue1234567890"), false, Map.of());

        String everything = result.env().toString() + result.requires().toString() + result.notices().toString();
        assertThat(everything).doesNotContain("hunter2verylongvalue1234567890");
    }

    @Test
    void dropsAnUnresolvedSubstitution() {
        Result result = classifier.classify(Map.of("REGION", "$_REGION"), false, Map.of());

        assertThat(result.env()).isEmpty();
        assertThat(result.notices()).anySatisfy(n -> assertThat(n.code()).isEqualTo(GenerationNoticeCode.SUBSTITUTION_UNRESOLVED));
    }

    @Test
    void rewritesAnotherImportedServicesUrlToAServicesPlaceholder() {
        Result result = classifier.classify(
                Map.of("BACKEND_URL", "https://api-abc123-uc.a.run.app"), false,
                Map.of("api", "https://api-abc123-uc.a.run.app"));

        assertThat(result.env()).containsEntry("BACKEND_URL", "${services.api.url}");
    }

    @Test
    void keepsAnOrdinaryLiteralValueAsIs() {
        Result result = classifier.classify(Map.of("LOG_LEVEL", "info"), false, Map.of());

        assertThat(result.env()).containsEntry("LOG_LEVEL", "info");
        assertThat(result.notices()).isEmpty();
    }

    @Test
    void escapesALiteralDollarBraceSoItIsNeverReadAsAPlaceholder() {
        Result result = classifier.classify(Map.of("TEMPLATE", "Hello ${name}"), false, Map.of());

        assertThat(result.env()).containsEntry("TEMPLATE", "Hello $${name}");
    }

    @Test
    void aBlankOrNullEnvMapProducesAnEmptyResult() {
        assertThat(classifier.classify(null, false, Map.of()).env()).isEmpty();
        assertThat(classifier.classify(Map.of(), false, Map.of()).env()).isEmpty();
    }
}
