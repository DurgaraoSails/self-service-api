package com.sails.ai.selfserviceapi.deploypipeline.github;

/** One tag as GitHub's tag-listing endpoint reports it — no date, no order guarantee (see {@code GitHubService.listTags}). */
public record GitHubTag(String name, String commitSha) {
}
