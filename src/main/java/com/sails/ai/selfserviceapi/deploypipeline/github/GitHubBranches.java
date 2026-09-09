package com.sails.ai.selfserviceapi.deploypipeline.github;

import java.util.List;

/**
 * A repository's branch names, in the order GitHub returns them (alphabetical).
 *
 * @param names     branch names, capped — see {@code GitHubService.listBranches}.
 * @param truncated the repository has more branches than were read. The admin form keeps a
 *                  type-it-yourself escape hatch open when this is true, so a branch past the cap
 *                  is still reachable rather than silently absent from the only way to choose one.
 */
public record GitHubBranches(List<String> names, boolean truncated) {
}
