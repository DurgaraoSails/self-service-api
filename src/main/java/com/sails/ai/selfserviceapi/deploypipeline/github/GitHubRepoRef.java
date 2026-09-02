package com.sails.ai.selfserviceapi.deploypipeline.github;

/** Owner/name pair parsed from a POC's githubUrl. */
public record GitHubRepoRef(String owner, String name) {

    @Override
    public String toString() {
        return owner + "/" + name;
    }
}
