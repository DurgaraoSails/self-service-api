package com.sails.ai.selfserviceapi.deploypipeline.run;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.sails.ai.selfserviceapi.deploypipeline.config.GcpProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Fetches a deployed service's hosted URL via the Cloud Run Admin API, rather than parsing it out
 * of {@code gcloud} output or Cloud Build logs. This is deliberately the one place either
 * executor asks "what URL did that deploy just get?" — the local executor's subprocess and Cloud
 * Build's own deploy step both just run {@code gcloud run deploy}; only this class needs to know
 * how to read the result back.
 */
@Service
public class CloudRunService {

    private final RestClient cloudRunRestClient;
    private final GcpProperties gcp;

    public CloudRunService(RestClient cloudRunRestClient, GcpProperties gcp) {
        this.cloudRunRestClient = cloudRunRestClient;
        this.gcp = gcp;
    }

    public String getServiceUrl(String slug) {
        try {
            ServiceResource service = cloudRunRestClient.get()
                    .uri("/v2/projects/{project}/locations/{region}/services/{slug}",
                            gcp.projectId(), gcp.region(), slug)
                    .retrieve()
                    .body(ServiceResource.class);
            return service.uri();
        } catch (RestClientResponseException e) {
            throw new CloudRunApiException("Failed to fetch the URL for '%s': %d %s"
                    .formatted(slug, e.getStatusCode().value(), e.getResponseBodyAsString()), e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ServiceResource(String uri) {
    }
}
