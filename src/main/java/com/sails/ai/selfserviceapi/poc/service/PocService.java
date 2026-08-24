package com.sails.ai.selfserviceapi.poc.service;

import com.sails.ai.selfserviceapi.poc.entity.Poc;
import com.sails.ai.selfserviceapi.poc.exception.PocNotFoundException;
import com.sails.ai.selfserviceapi.poc.repository.PocRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PocService {

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
    public Poc create(String name, String description, String iconUrl, String appUrl, String githubUrl) {
        Poc poc = new Poc();
        poc.setName(name);
        poc.setDescription(description);
        poc.setIconUrl(iconUrl);
        poc.setAppUrl(appUrl);
        poc.setGithubUrl(githubUrl);
        return pocRepository.save(poc);
    }

    @Transactional
    public Poc update(Long id, String name, String description, String iconUrl, String appUrl, String githubUrl) {
        Poc poc = getById(id);
        poc.setName(name);
        poc.setDescription(description);
        poc.setIconUrl(iconUrl);
        poc.setAppUrl(appUrl);
        poc.setGithubUrl(githubUrl);
        return pocRepository.save(poc);
    }

    @Transactional
    public void delete(Long id) {
        if (!pocRepository.existsById(id)) {
            throw new PocNotFoundException(id);
        }
        pocRepository.deleteById(id);
    }
}
