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
    public Poc create(String name, String description, String iconUrl, String appUrl, String githubUrl,
                       String version, String owner, String category, List<String> technologies,
                       String containerImage, String demoType, String status) {
        Poc poc = new Poc();
        applyFields(poc, name, description, iconUrl, appUrl, githubUrl, version, owner, category,
                technologies, containerImage, demoType, status);
        return pocRepository.save(poc);
    }

    @Transactional
    public Poc update(Long id, String name, String description, String iconUrl, String appUrl, String githubUrl,
                       String version, String owner, String category, List<String> technologies,
                       String containerImage, String demoType, String status) {
        Poc poc = getById(id);
        applyFields(poc, name, description, iconUrl, appUrl, githubUrl, version, owner, category,
                technologies, containerImage, demoType, status);
        return pocRepository.save(poc);
    }

    @Transactional
    public void delete(Long id) {
        if (!pocRepository.existsById(id)) {
            throw new PocNotFoundException(id);
        }
        pocRepository.deleteById(id);
    }

    private void applyFields(Poc poc, String name, String description, String iconUrl, String appUrl,
                              String githubUrl, String version, String owner, String category,
                              List<String> technologies, String containerImage, String demoType, String status) {
        poc.setName(name);
        poc.setDescription(description);
        poc.setIconUrl(iconUrl);
        poc.setAppUrl(appUrl);
        poc.setGithubUrl(githubUrl);
        poc.setVersion(version);
        poc.setOwner(owner);
        poc.setCategory(category);
        poc.setTechnologies(technologies != null ? technologies : new ArrayList<>());
        poc.setContainerImage(containerImage);
        poc.setDemoType(demoType);
        poc.setStatus(status != null ? status : DEFAULT_STATUS);
    }
}
