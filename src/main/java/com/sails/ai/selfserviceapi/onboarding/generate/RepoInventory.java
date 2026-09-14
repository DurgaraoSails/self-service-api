package com.sails.ai.selfserviceapi.onboarding.generate;

import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubTree;
import java.util.List;

/**
 * What {@link RepoInventoryService} decided a model needs to see: the full path list (so the model
 * can be told what exists even for files not read in full) plus the bounded set of files actually
 * fetched.
 *
 * @param tree          the repository's full tree — used for path listing and Dockerfile discovery,
 *                       never sent to the model in full.
 * @param evidenceFiles the capped set of files whose content was read, in selection-priority order.
 * @param treeTruncated the tree itself was too large for GitHub to return in one response, so even
 *                       the path list may be incomplete — surfaced to the caller rather than acted
 *                       on silently.
 */
public record RepoInventory(GitHubTree tree, List<EvidenceFile> evidenceFiles, boolean treeTruncated) {

    public record EvidenceFile(String path, String content) {
    }
}
