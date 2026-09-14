package com.sails.ai.selfserviceapi.deploypipeline.github;

import java.util.List;

/**
 * Every file and directory in a repository at one commit, flattened by GitHub's own
 * {@code recursive=1} tree read — see {@code GitHubService.listTree}.
 *
 * @param entries   every blob and tree entry GitHub returned.
 * @param truncated the repository is larger than GitHub will flatten in one response. A caller
 *                  that relies on this tree to prove a file's *absence* cannot do so when true —
 *                  a missing entry may simply not have been read.
 */
public record GitHubTree(List<GitHubTreeEntry> entries, boolean truncated) {
}
