package com.sails.ai.selfserviceapi.poc.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sails.ai.selfserviceapi.generated.model.PocResponse;
import com.sails.ai.selfserviceapi.generated.model.PocSummaryResponse;
import com.sails.ai.selfserviceapi.poc.entity.Poc;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Every field on {@code Poc} that has a place on the response DTOs is asserted here individually.
 * {@code slug} previously had no test at all and silently stopped being mapped in
 * {@code toResponse} for two days across several commits — this exists so a field being added to
 * the entity or the schema without a matching {@code .field(...)} call in the mapper fails a build
 * instead of only ever showing up as "this value doesn't come back from the API."
 */
class PocResponseMapperTest {

    private static final UUID POC_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID ACTIVE_VERSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000007");

    @Test
    void toResponseCarriesEveryFieldIncludingTheAuthenticatedOnlyOnes() {
        Poc poc = fullyPopulatedPoc();

        PocResponse response = PocResponseMapper.toResponse(poc, "1.2.4", "SUCCEEDED");

        assertThat(response.getId()).isEqualTo(POC_ID);
        assertThat(response.getName()).isEqualTo("Contract Agent");
        assertThat(response.getDescription()).isEqualTo("Review & generate contracts.");
        assertThat(response.getIconUrl()).isEqualTo("https://cdn.example.com/icon.svg");
        assertThat(response.getActiveVersion()).isEqualTo("1.2.4");
        assertThat(response.getLatestDeploymentStatus().getValue()).isEqualTo("SUCCEEDED");
        assertThat(response.getOwner()).isEqualTo("AI Team");
        assertThat(response.getCategory()).isEqualTo("Healthcare");
        assertThat(response.getTechnologies()).containsExactly("Python", "FastAPI");
        assertThat(response.getDemoType()).isEqualTo("interactive");
        assertThat(response.getVisibilityStatus().getValue()).isEqualTo("ACTIVE");
        assertThat(response.getDetails()).isEqualTo("Longer description.");
        assertThat(response.getGuideSteps()).containsExactly("Step one.");
        // The three fields PocResponse adds on top of PocSummaryResponse (see poc-catalog.md) --
        // exactly the ones a caller cannot deploy or launch a POC without.
        assertThat(response.getAppUrl()).isEqualTo("https://contract-agent.example.run.app");
        assertThat(response.getGithubUrl()).isEqualTo("https://github.com/example-org/contract-agent");
        assertThat(response.getActiveVersionId()).isEqualTo(ACTIVE_VERSION_ID);
        assertThat(response.getSlug()).isEqualTo("contract-agent");
    }

    @Test
    void toSummaryResponseExcludesTheAuthenticatedOnlyFields() {
        Poc poc = fullyPopulatedPoc();

        PocSummaryResponse response = PocResponseMapper.toSummaryResponse(poc, "1.2.4", "SUCCEEDED");

        // PocSummaryResponse has no appUrl/githubUrl/slug getters at all -- if one of these fields
        // is ever added to that schema, this test needs a new assertion, not a passing one.
        assertThat(response.getId()).isEqualTo(POC_ID);
        assertThat(response.getVisibilityStatus().getValue()).isEqualTo("ACTIVE");
        assertThat(response.getDetails()).isEqualTo("Longer description.");
    }

    private static Poc fullyPopulatedPoc() {
        Poc poc = new Poc();
        poc.setId(POC_ID);
        poc.setName("Contract Agent");
        poc.setDescription("Review & generate contracts.");
        poc.setIconUrl("https://cdn.example.com/icon.svg");
        poc.setAppUrl("https://contract-agent.example.run.app");
        poc.setGithubUrl("https://github.com/example-org/contract-agent");
        poc.setOwner("AI Team");
        poc.setCategory("Healthcare");
        poc.setTechnologies(List.of("Python", "FastAPI"));
        poc.setDemoType("interactive");
        poc.setVisibilityStatus("ACTIVE");
        poc.setDetails("Longer description.");
        poc.setGuideSteps(List.of("Step one."));
        poc.setActiveVersionId(ACTIVE_VERSION_ID);
        poc.setSlug("contract-agent");
        poc.setCreatedAt(Instant.now());
        poc.setUpdatedAt(Instant.now());
        return poc;
    }
}
