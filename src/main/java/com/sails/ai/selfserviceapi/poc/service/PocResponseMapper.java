package com.sails.ai.selfserviceapi.poc.service;

import com.sails.ai.selfserviceapi.generated.model.PocResponse;
import com.sails.ai.selfserviceapi.generated.model.PocSummaryResponse;
import com.sails.ai.selfserviceapi.poc.entity.Poc;

public final class PocResponseMapper {

    private PocResponseMapper() {
    }

    public static PocSummaryResponse toSummaryResponse(Poc poc) {
        return new PocSummaryResponse(poc.getId(), poc.getName(), poc.getDescription(), poc.getStatus())
                .iconUrl(poc.getIconUrl())
                .version(poc.getVersion())
                .owner(poc.getOwner())
                .category(poc.getCategory())
                .technologies(poc.getTechnologies())
                .demoType(poc.getDemoType());
    }

    public static PocResponse toResponse(Poc poc) {
        return new PocResponse(poc.getId(), poc.getName(), poc.getDescription(), poc.getStatus())
                .iconUrl(poc.getIconUrl())
                .version(poc.getVersion())
                .owner(poc.getOwner())
                .category(poc.getCategory())
                .technologies(poc.getTechnologies())
                .demoType(poc.getDemoType())
                .appUrl(poc.getAppUrl())
                .githubUrl(poc.getGithubUrl())
                .containerImage(poc.getContainerImage());
    }
}
