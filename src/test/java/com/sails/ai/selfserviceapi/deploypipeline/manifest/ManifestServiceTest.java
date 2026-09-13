package com.sails.ai.selfserviceapi.deploypipeline.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sails.ai.selfserviceapi.deploypipeline.config.PocRuntimeProperties;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubRepoRef;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubService;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Which file a manifest is read from, and what happens when there isn't one. The rest of resolution
 * is covered by {@link ManifestParserTest} and {@link ManifestValidatorTest}; this is about the
 * lookup itself, which is the part a POC team notices when it silently finds nothing.
 */
class ManifestServiceTest {

    private static final GitHubRepoRef REPO = new GitHubRepoRef("acme", "contract-agent");
    private static final String SHA = "abc123";

    private static final String MANIFEST = """
            containers:
              - name: web
                role: ingress
            """;

    private final GitHubService gitHubService = mock(GitHubService.class);

    private final ManifestService service = new ManifestService(gitHubService, new ManifestParser(),
            new ManifestValidator(new ManifestProperties(null, null, 8),
                    new PocRuntimeProperties(8080, "https://api.example.com", "https://portal.example.com")));

    @Test
    void readsPocYamlAndDoesNotLookAnyFurther() {
        when(gitHubService.getFileContent(REPO, SHA, "poc.yaml")).thenReturn(Optional.of(MANIFEST));

        ManifestResolution resolution = service.resolveForBuild(REPO, SHA);

        assertThat(resolution.rawYaml()).isEqualTo(MANIFEST);
        assertThat(resolution.manifest().containers()).singleElement()
                .satisfies(container -> assertThat(container.name()).isEqualTo("web"));
        verify(gitHubService, never()).getFileContent(REPO, SHA, "poc.yml");
    }

    /**
     * The gap this closes: the other spelling used to fall through to the synthesized default, so a
     * team's manifest was ignored with nothing anywhere saying why.
     */
    @Test
    void fallsBackToPocYmlWhenPocYamlIsAbsent() {
        when(gitHubService.getFileContent(REPO, SHA, "poc.yaml")).thenReturn(Optional.empty());
        when(gitHubService.getFileContent(REPO, SHA, "poc.yml")).thenReturn(Optional.of(MANIFEST));

        ManifestResolution resolution = service.resolveForBuild(REPO, SHA);

        assertThat(resolution.rawYaml()).isEqualTo(MANIFEST);
        assertThat(resolution.manifest().containers()).singleElement()
                .satisfies(container -> assertThat(container.name()).isEqualTo("web"));
    }

    @Test
    void synthesizesTheSingleContainerDefaultWhenNeitherSpellingExists() {
        when(gitHubService.getFileContent(REPO, SHA, "poc.yaml")).thenReturn(Optional.empty());
        when(gitHubService.getFileContent(REPO, SHA, "poc.yml")).thenReturn(Optional.empty());

        ManifestResolution resolution = service.resolveForBuild(REPO, SHA);

        assertThat(resolution.rawYaml()).isNull();
        assertThat(resolution.manifest().containers()).singleElement().satisfies(container -> {
            assertThat(container.name()).isEqualTo("app");
            assertThat(container.role()).isEqualTo(ContainerRole.INGRESS);
            assertThat(container.dockerfile()).isEqualTo("Dockerfile");
        });
    }

    @Test
    void validatesTheFallbackSpellingJustAsStrictly() {
        when(gitHubService.getFileContent(REPO, SHA, "poc.yaml")).thenReturn(Optional.empty());
        when(gitHubService.getFileContent(REPO, SHA, "poc.yml")).thenReturn(Optional.of("""
                containers:
                  - name: web
                    role: ingress
                  - name: api
                    role: sidecar
                """));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.resolveForBuild(REPO, SHA))
                .isInstanceOf(ManifestValidationException.class);
    }
}
