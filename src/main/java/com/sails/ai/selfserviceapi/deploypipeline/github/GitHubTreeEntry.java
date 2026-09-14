package com.sails.ai.selfserviceapi.deploypipeline.github;

/**
 * One entry from a repository's tree.
 *
 * @param path a full path from the repository root, e.g. {@code apps/web/Dockerfile}.
 * @param type {@code "blob"} for a file, {@code "tree"} for a directory, {@code "commit"} for a
 *             submodule. Most callers care only about blobs.
 * @param size the blob's size in bytes, as GitHub reports it. {@code null} for a tree or submodule
 *             entry, which have no size of their own.
 */
public record GitHubTreeEntry(String path, String type, Long size) {

    public boolean isBlob() {
        return "blob".equals(type);
    }
}
