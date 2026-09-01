package com.sails.ai.selfserviceapi.poc.service;

import com.sails.ai.selfserviceapi.poc.entity.Poc;
import com.sails.ai.selfserviceapi.poc.entity.PocCategory;
import com.sails.ai.selfserviceapi.poc.exception.PocNotFoundException;
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
            return pocRepository.findByStatusAndDeletedAtIsNull(DEFAULT_STATUS);
        }
        return includeDeleted ? pocRepository.findAll() : pocRepository.findByDeletedAtIsNull();
    }

    public Poc getById(Long id) {
        return pocRepository.findById(id)
                .orElseThrow(() -> new PocNotFoundException(id));
    }

    public List<PocCategory> listCategories() {
        return pocCategoryRepository.findAllByOrderByNameAsc();
    }

    /** Every POC the pipeline can poll for upstream changes — i.e. those with a repository. */
    public List<Poc> listSourceRepositories() {
        return pocRepository.findByGithubUrlIsNotNullAndDeletedAtIsNull();
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
        poc.setStatus(HIDDEN_STATUS);
        return pocRepository.save(poc);
    }

    @Transactional
    public Poc unhide(Long id) {
        Poc poc = getById(id);
        poc.setStatus(DEFAULT_STATUS);
        return pocRepository.save(poc);
    }

    private void applyFields(Poc poc, PocFields fields) {
        poc.setName(fields.name());
        poc.setDescription(fields.description());
        poc.setIconUrl(fields.iconUrl());
        poc.setAppUrl(fields.appUrl());
        poc.setGithubUrl(fields.githubUrl());
        poc.setOwner(fields.owner());
        poc.setCategory(fields.category());
        poc.setTechnologies(fields.technologies() != null ? fields.technologies() : new ArrayList<>());
        poc.setDemoType(fields.demoType());
        poc.setStatus(fields.status() != null ? fields.status() : DEFAULT_STATUS);
        poc.setDetails(fields.details());
        poc.setGuideSteps(fields.guideSteps() != null ? fields.guideSteps() : new ArrayList<>());
    }
}
