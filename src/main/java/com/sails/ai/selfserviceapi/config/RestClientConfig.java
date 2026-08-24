package com.sails.ai.selfserviceapi.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    @Value("${brevo.api-key}")
    private String brevoApiKey;

    @Bean
    public RestClient brevoRestClient() {
        System.out.println("BREVO_API_KEY = [" + System.getenv("BREVO_API_KEY") + "]");
        return RestClient.builder()
                .baseUrl("https://api.brevo.com/v3")
                .defaultHeader("api-key", brevoApiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }
}
