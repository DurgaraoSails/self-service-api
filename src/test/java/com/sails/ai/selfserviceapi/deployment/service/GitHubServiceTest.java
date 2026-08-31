package com.sails.ai.selfserviceapi.deployment.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;

/**
 * Hits the real GitHub API against DurgaraoSails/dummy-poc. Only runs when GITHUB_TOKEN is set
 * in the environment — skipped everywhere else (including CI), so it never breaks the build for
 * anyone without a token.
 *
 * Run directly: mvnw test -Dtest=GitHubServiceIT
 */
@EnabledIfEnvironmentVariable(named = "GITHUB_TOKEN", matches = ".+")
class GitHubServiceTest {

    private static final String REPO_URL = "https://github.com/DurgaraoSails/dummy-poc";

    private GitHubService gitHubService;
    private GitHubRepoRef dummyPoc;

    @BeforeEach
    void setUp() {
        gitHubService = new GitHubService(buildClient());
        dummyPoc = gitHubService.parseRepoUrl(REPO_URL);
    }

    @Test
    void resolvesDefaultBranchHeadShaAndCreatesATag() {
        String defaultBranch = gitHubService.getDefaultBranch(dummyPoc);
        assertThat(defaultBranch).isEqualTo("main");

        String headSha = gitHubService.getBranchHeadSha(dummyPoc, defaultBranch);
        assertThat(headSha).hasSize(40);

        List<String> tagsBefore = gitHubService.listTagNames(dummyPoc);

        String testTag = "test-" + UUID.randomUUID().toString().substring(0, 8);
        gitHubService.createTag(dummyPoc, testTag, headSha);

        try {
            List<String> tagsAfter = gitHubService.listTagNames(dummyPoc);
            assertThat(tagsAfter).contains(testTag).hasSize(tagsBefore.size() + 1);
        } finally {
            deleteTag(testTag);
        }
    }

    private void deleteTag(String tagName) {
        buildClient().delete()
                .uri("/repos/{owner}/{repo}/git/refs/tags/{tag}", dummyPoc.owner(), dummyPoc.name(), tagName)
                .retrieve()
                .toBodilessEntity();
    }

    private RestClient buildClient() {
        return RestClient.builder()
                .baseUrl("https://api.github.com")
                .defaultHeader("Authorization", "Bearer " + System.getenv("GITHUB_TOKEN"))
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }
}
