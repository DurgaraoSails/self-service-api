package com.sails.ai.selfserviceapi.asset.config;

import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Voyage AI REST client for {@link com.sails.ai.selfserviceapi.asset.ai.VoyageEmbeddingProvider}.
 * Voyage authenticates with a plain bearer API key (no ADC/service-account flow like Vertex AI),
 * so this is simpler than {@code AssetAiClientConfig}: no lazy credential resolution needed, just
 * a static bearer header built from config that is only ever read when semantic search is enabled.
 */
@Configuration
@ConditionalOnProperty(prefix = "asset-hub", name = "semantic-search-enabled", havingValue = "true")
public class AssetEmbeddingClientConfig {

    @Bean
    public RestClient voyageRestClient(AssetHubProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        int timeoutMillis = (int) Duration.ofSeconds(properties.embedding().timeoutSeconds()).toMillis();
        requestFactory.setConnectTimeout(timeoutMillis);
        requestFactory.setReadTimeout(timeoutMillis);

        return RestClient.builder()
                .baseUrl(properties.embedding().baseUrl())
                .requestFactory(requestFactory)
                .defaultHeader("Authorization", "Bearer " + properties.embedding().apiKey())
                .build();
    }
}
