package com.sails.ai.selfserviceapi.deployment.service;

/** Owner/name pair parsed from a POC's stored githubUrl. */
public record GitHubRepoRef(String owner, String name) {

    @Override
    public String toString() {
        return owner + "/" + name;
    }
}
