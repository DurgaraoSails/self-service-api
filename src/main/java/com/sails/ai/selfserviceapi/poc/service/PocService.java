package com.sails.ai.selfserviceapi.poc.service;

import com.sails.ai.selfserviceapi.poc.entity.Poc;
import com.sails.ai.selfserviceapi.poc.exception.PocNotFoundException;
import com.sails.ai.selfserviceapi.poc.repository.PocRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PocService {

    private static final String DEFAULT_STATUS = "ACTIVE";

    private final PocRepository pocRepository;

    public PocService(PocRepository pocRepository) {
        this.pocRepository = pocRepository;
    }

    public List<Poc> listAll() {
        return pocRepository.findAll();
    }

    public Poc getById(Long id) {
        return pocRepository.findById(id)
                .orElseThrow(() -> new PocNotFoundException(id));
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
        if (!pocRepository.existsById(id)) {
            throw new PocNotFoundException(id);
        }
        pocRepository.deleteById(id);
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
