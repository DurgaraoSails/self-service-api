package com.sails.ai.selfserviceapi.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    @Value("${brevo.api-key}")
    private String brevoApiKey;

    @Value("${github.token}")
    private String gitHubToken;

    @Bean
    public RestClient brevoRestClient() {
        return RestClient.builder()
                .baseUrl("https://api.brevo.com/v3")
                .defaultHeader("api-key", brevoApiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @Bean
    public RestClient gitHubRestClient() {
        return RestClient.builder()
                .baseUrl("https://api.github.com")
                .defaultHeader("Authorization", "Bearer " + gitHubToken)
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }
}
