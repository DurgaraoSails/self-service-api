package com.sails.ai.selfserviceapi.poc.service;

import com.sails.ai.selfserviceapi.poc.entity.Poc;
import com.sails.ai.selfserviceapi.poc.exception.PocNotFoundException;
import com.sails.ai.selfserviceapi.poc.exception.PocSlugAlreadyExistsException;
import com.sails.ai.selfserviceapi.poc.repository.PocRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PocService {

    private static final String DEFAULT_STATUS = "ACTIVE";
    private static final String HIDDEN_STATUS = "HIDDEN";
    private static final String DEPLOYMENT_ACTIVE = "active";
    private static final String DEPLOYMENT_NOT_DEPLOYED = "not_deployed";

    private final PocRepository pocRepository;

    public PocService(PocRepository pocRepository) {
        this.pocRepository = pocRepository;
    }

    /**
     * Non-admins only ever see active, non-deleted POCs. Admins additionally see hidden POCs,
     * and soft-deleted ones too when {@code includeDeleted} is set. Either way, a POC still
     * building/not yet deployed/failed never shows here — it's not "ready", regardless of who's
     * asking; its progress is tracked via the deployments/latest endpoint instead.
     */
    public List<Poc> listForViewer(boolean isAdmin, boolean includeDeleted) {
        List<Poc> pocs;
        if (!isAdmin) {
            pocs = pocRepository.findByStatusAndDeletedAtIsNull(DEFAULT_STATUS);
        } else {
            pocs = includeDeleted ? pocRepository.findAll() : pocRepository.findByDeletedAtIsNull();
        }
        return pocs.stream()
                .filter(poc -> DEPLOYMENT_ACTIVE.equals(poc.getDeploymentStatus()))
                .toList();
    }

    public Poc getById(Long id) {
        return pocRepository.findById(id)
                .orElseThrow(() -> new PocNotFoundException(id));
    }

    public Poc getBySlug(String slug) {
        return pocRepository.findBySlug(slug)
                .orElseThrow(() -> new PocNotFoundException(slug));
    }

    @Transactional
    public Poc create(PocFields fields) {
        Poc poc = new Poc();
        applyFields(poc, fields);
        return pocRepository.save(poc);
    }

    /** Used by the self-service pipeline flow — every POC created this way starts undeployed. */
    @Transactional
    public Poc createForPipeline(PocFields fields, String slug) {
        // The unique constraint on pocs.slug is the real guard; this pre-check exists so a
        // duplicate returns a 409 the admin can act on rather than a bare 500 from the
        // constraint violation surfacing as an unhandled DataIntegrityViolationException.
        if (pocRepository.findBySlug(slug).isPresent()) {
            throw new PocSlugAlreadyExistsException(slug);
        }

        Poc poc = new Poc();
        applyFields(poc, fields);
        poc.setSlug(slug);
        poc.setDeploymentStatus(DEPLOYMENT_NOT_DEPLOYED);
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
        poc.setVersion(fields.version());
        poc.setOwner(fields.owner());
        poc.setCategory(fields.category());
        poc.setTechnologies(fields.technologies() != null ? fields.technologies() : new ArrayList<>());
        poc.setContainerImage(fields.containerImage());
        poc.setDemoType(fields.demoType());
        poc.setStatus(fields.status() != null ? fields.status() : DEFAULT_STATUS);
        poc.setDetails(fields.details());
        poc.setGuideSteps(fields.guideSteps() != null ? fields.guideSteps() : new ArrayList<>());
    }
}
