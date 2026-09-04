package com.sails.ai.selfserviceapi.poc.service;

import com.sails.ai.selfserviceapi.poc.entity.Poc;
import com.sails.ai.selfserviceapi.poc.entity.PocCategory;
import com.sails.ai.selfserviceapi.poc.exception.PocNotFoundException;
import com.sails.ai.selfserviceapi.poc.exception.PocNotLaunchableException;
import com.sails.ai.selfserviceapi.poc.repository.PocCategoryRepository;
import com.sails.ai.selfserviceapi.poc.repository.PocRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PocService {

    private static final String DEFAULT_STATUS = "ACTIVE";
    private static final String HIDDEN_STATUS = "HIDDEN";

    private final PocRepository pocRepository;
    private final PocCategoryRepository pocCategoryRepository;

    public PocService(PocRepository pocRepository, PocCategoryRepository pocCategoryRepository) {
        this.pocRepository = pocRepository;
        this.pocCategoryRepository = pocCategoryRepository;
    }

    /**
     * Non-admins only ever see active, non-deleted POCs. Admins additionally see hidden POCs,
     * and soft-deleted ones too when {@code includeDeleted} is set.
     */
    public List<Poc> listForViewer(boolean isAdmin, boolean includeDeleted) {
        if (!isAdmin) {
            return pocRepository.findByVisibilityStatusAndDeletedAtIsNull(DEFAULT_STATUS);
        }
        return includeDeleted ? pocRepository.findAll() : pocRepository.findByDeletedAtIsNull();
    }

    public Poc getById(Long id) {
        return pocRepository.findById(id)
                .orElseThrow(() -> new PocNotFoundException(id));
    }

    /**
     * Resolves a POC for POST /pocs/{slug}/launch. A missing slug and a HIDDEN one look
     * identical to a non-admin caller — 404 either way — mirroring {@link #listForViewer} so
     * hidden POCs don't leak their existence via this endpoint either. A POC that is visible but
     * was never deployed (no appUrl) is a distinct, admin-fixable problem, so it gets its own
     * 409 rather than being folded into "not found".
     */
    public Poc getLaunchable(String slug, boolean isAdmin) {
        Poc poc = pocRepository.findBySlugAndDeletedAtIsNull(slug)
                .orElseThrow(() -> new PocNotFoundException(slug));

        if (!isAdmin && HIDDEN_STATUS.equals(poc.getVisibilityStatus())) {
            throw new PocNotFoundException(slug);
        }

        if (poc.getAppUrl() == null || poc.getAppUrl().isBlank()) {
            throw new PocNotLaunchableException(slug);
        }

        return poc;
    }

    public List<PocCategory> listCategories() {
        return pocCategoryRepository.findAllByOrderByNameAsc();
    }

    /** Every POC the pipeline can poll for upstream changes — i.e. those with a repository. */
    public List<Poc> listSourceRepositories() {
        return pocRepository.findByGithubUrlIsNotNullAndDeletedAtIsNull();
    }

    /** Every POC that actually can be deployed — has both a githubUrl and a slug. */
    public List<Poc> listDeployable() {
        return pocRepository.findByGithubUrlIsNotNullAndSlugIsNotNullAndDeletedAtIsNull();
    }

    /**
     * Records what the pipeline observed at each repository's head. Unknown ids are skipped
     * rather than failing the batch: a POC deleted between the pipeline listing repositories
     * and reporting back should not discard the rest of the run's results.
     */
    @Transactional
    public void recordUpstreamCommits(Map<Long, String> commitShaByPocId) {
        Instant checkedAt = Instant.now();

        for (Poc poc : pocRepository.findAllById(commitShaByPocId.keySet())) {
            poc.setLatestMainCommitSha(commitShaByPocId.get(poc.getId()));
            poc.setLatestMainCheckedAt(checkedAt);
            pocRepository.save(poc);
        }
    }

    @Transactional
    public Poc create(PocFields fields) {
        Poc poc = new Poc();
        applyFields(poc, fields);
        return pocRepository.save(poc);
    }

    @Transactional
    public Poc update(Long id, PocFields fields) {
        Poc poc = getById(id);
        applyFields(poc, fields);
        return pocRepository.save(poc);
    }

    @Transactional
    public void delete(Long id) {
        Poc poc = getById(id);
        poc.setDeletedAt(Instant.now());
        pocRepository.save(poc);
    }

    @Transactional
    public Poc restore(Long id) {
        Poc poc = getById(id);
        poc.setDeletedAt(null);
        return pocRepository.save(poc);
    }

    @Transactional
    public Poc hide(Long id) {
        Poc poc = getById(id);
        poc.setVisibilityStatus(HIDDEN_STATUS);
        return pocRepository.save(poc);
    }

    @Transactional
    public Poc unhide(Long id) {
        Poc poc = getById(id);
        poc.setVisibilityStatus(DEFAULT_STATUS);
        return pocRepository.save(poc);
    }

    private void applyFields(Poc poc, PocFields fields) {
        poc.setName(fields.name());
        poc.setDescription(fields.description());
        // Set once, never changed. The slug names a deployed Cloud Run service and an Artifact
        // Registry path; editing it would orphan both and leave the POC pointing at nothing.
        // Still settable on update while null, so POCs predating the pipeline can be adopted.
        if (poc.getSlug() == null && fields.slug() != null && !fields.slug().isBlank()) {
            poc.setSlug(fields.slug());
        }
        poc.setIconUrl(fields.iconUrl());
        poc.setAppUrl(fields.appUrl());
        poc.setGithubUrl(fields.githubUrl());
        poc.setOwner(fields.owner());
        poc.setCategory(fields.category());
        poc.setTechnologies(fields.technologies() != null ? fields.technologies() : new ArrayList<>());
        poc.setDemoType(fields.demoType());
        poc.setVisibilityStatus(fields.visibilityStatus() != null ? fields.visibilityStatus() : DEFAULT_STATUS);
        poc.setDetails(fields.details());
        poc.setGuideSteps(fields.guideSteps() != null ? fields.guideSteps() : new ArrayList<>());
    }
}
