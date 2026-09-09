package com.sails.ai.selfserviceapi.poc.service;

import com.sails.ai.selfserviceapi.deploypipeline.config.AsyncConfig;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubApiException;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubRepoRef;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubService;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubService.RepoAccess;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubTag;
import com.sails.ai.selfserviceapi.poc.entity.Poc;
import com.sails.ai.selfserviceapi.poc.entity.PocRepoStatus;
import com.sails.ai.selfserviceapi.poc.entity.PocRepoTag;
import com.sails.ai.selfserviceapi.poc.repository.PocRepoStatusRepository;
import com.sails.ai.selfserviceapi.poc.repository.PocRepoTagRepository;
import com.sails.ai.selfserviceapi.poc.repository.PocRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Reads a POC's repository state from GitHub and stores it — the only place this platform's
 * deployment page ever gets that state from is this snapshot, never a fresh GitHub call at read
 * time. See docs/specs/poc-tag-driven-deployment.md, "The snapshot, and why it is not a cache."
 *
 * <p>Refreshed on exactly three occasions — POC creation, an explicit admin refresh, and after a
 * successful deploy (the tag list has certainly changed) — and never expired. A visibly stale
 * snapshot with a timestamp and a refresh button is safer here than one that silently vanishes
 * mid-session.
 */
@Service
public class PocRepoStatusService {

    private static final Logger log = LoggerFactory.getLogger(PocRepoStatusService.class);

    /** The version list shows the 3 most recent tags. */
    private static final int TAG_FETCH_COUNT = 3;

    private final PocRepository pocRepository;
    private final PocRepoStatusRepository pocRepoStatusRepository;
    private final PocRepoTagRepository pocRepoTagRepository;
    private final GitHubService gitHubService;

    public PocRepoStatusService(PocRepository pocRepository, PocRepoStatusRepository pocRepoStatusRepository,
                                 PocRepoTagRepository pocRepoTagRepository, GitHubService gitHubService) {
        this.pocRepository = pocRepository;
        this.pocRepoStatusRepository = pocRepoStatusRepository;
        this.pocRepoTagRepository = pocRepoTagRepository;
        this.gitHubService = gitHubService;
    }

    /**
     * Fire-and-forget from the caller's point of view — this runs on {@link AsyncConfig}'s bounded
     * pool, not the calling thread, so POC creation and a just-finished deploy both return without
     * waiting on GitHub. A POC with no githubUrl yet has nothing to refresh and is silently skipped
     * rather than treated as a failure — every caller of this method already knows to only call it
     * for a POC that has one, but a POC's URL can be cleared between scheduling and running.
     */
    @Async(AsyncConfig.PIPELINE_EXECUTOR)
    public void refresh(UUID pocId) {
        pocRepository.findById(pocId).ifPresent(this::refreshNow);
    }

    /** Absent if no refresh has ever completed for this POC — e.g. immediately after creation, before the async refresh finishes. */
    public Optional<PocRepoStatus> find(UUID pocId) {
        return pocRepoStatusRepository.findById(pocId);
    }

    /** GitHub's own newest-first order, as stored at the last refresh. */
    public List<PocRepoTag> listTags(UUID pocId) {
        return pocRepoTagRepository.findByPocIdOrderByPositionAsc(pocId);
    }

    /**
     * The synchronous half, exposed separately so a caller that already holds the {@link Poc} (the
     * manual-refresh endpoint, which needs to 404 on a missing POC rather than silently no-op)
     * does not have to re-fetch it, and so tests can drive this without waiting on {@code @Async}.
     *
     * <p>Deliberately not {@code @Transactional}: {@link #refresh} calls this via {@code this::},
     * which bypasses Spring's proxy and would make that annotation a no-op anyway. The two writes
     * below (the status row, then the tag rows) are each already atomic on their own via Spring
     * Data's own transactional {@code save}/{@code saveAll} — losing atomicity *across* the two
     * only matters if the process dies between them, and the next refresh (on-demand, or the next
     * deploy) simply overwrites both again. Not worth a real transaction boundary for a refresh
     * that is already best-effort by design (see the class javadoc).
     */
    public void refreshNow(Poc poc) {
        String githubUrl = poc.getGithubUrl();
        if (githubUrl == null || githubUrl.isBlank()) {
            return;
        }

        PocRepoStatus status = pocRepoStatusRepository.findById(poc.getId()).orElseGet(() -> {
            PocRepoStatus created = new PocRepoStatus();
            created.setPocId(poc.getId());
            return created;
        });

        try {
            GitHubRepoRef repo = gitHubService.parseRepoUrl(githubUrl);

            String defaultBranch = gitHubService.getDefaultBranch(repo);
            String deployBranch = gitHubService.resolveDeployBranch(repo, poc.getDeployBranch());
            String headCommitSha = gitHubService.getBranchHeadSha(repo, deployBranch);
            RepoAccess access = gitHubService.checkPushAccess(repo);
            List<GitHubTag> tags = gitHubService.listTags(repo, TAG_FETCH_COUNT);

            status.setDefaultBranch(defaultBranch);
            status.setDeployBranch(deployBranch);
            status.setHeadCommitSha(headCommitSha);
            status.setCanCreateTags(access == RepoAccess.OK);
            status.setArchived(access == RepoAccess.ARCHIVED);
            status.setVisible(access != RepoAccess.NOT_FOUND);
            status.setRefreshedAt(Instant.now());
            status.setRefreshError(null);
            pocRepoStatusRepository.save(status);

            replaceTags(poc.getId(), tags, headCommitSha);
        } catch (GitHubApiException e) {
            // Deliberately does not touch defaultBranch/deployBranch/headCommitSha/canCreateTags
            // or the stored tags — a refresh that failed must not make a repository with real,
            // previously-read state look like one with none. Only the error and its timestamp
            // change, so the UI can show "last refreshed 3 days ago, refresh failed just now"
            // rather than a repository that suddenly looks empty.
            log.warn("Repo status refresh failed for poc {}: {}", poc.getId(), e.getMessage());
            status.setRefreshedAt(Instant.now());
            status.setRefreshError(e.getMessage());
            pocRepoStatusRepository.save(status);
        }
    }

    private void replaceTags(UUID pocId, List<GitHubTag> tags, String headCommitSha) {
        pocRepoTagRepository.deleteByPocId(pocId);
        List<PocRepoTag> rows = new ArrayList<>();
        for (int i = 0; i < tags.size(); i++) {
            GitHubTag tag = tags.get(i);
            PocRepoTag row = new PocRepoTag();
            row.setPocId(pocId);
            row.setTagName(tag.name());
            row.setCommitSha(tag.commitSha());
            row.setCurrent(tag.commitSha().equals(headCommitSha));
            row.setPosition(i);
            rows.add(row);
        }
        pocRepoTagRepository.saveAll(rows);
    }
}
