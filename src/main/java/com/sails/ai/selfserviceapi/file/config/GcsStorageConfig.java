package com.sails.ai.selfserviceapi.file.config;

import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.sails.ai.selfserviceapi.deploypipeline.config.GcpProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The GCS client, built only when {@code files.storage=gcs} so a local run never tries to resolve
 * Application Default Credentials. Same shape as the pipeline's RestClient beans: the client is a
 * bean, so the storage implementation takes it as a constructor argument and stays testable.
 */
@Configuration
@ConditionalOnProperty(prefix = "files", name = "storage", havingValue = "gcs")
public class GcsStorageConfig {

    @Bean
    Storage gcsStorage(GcpProperties gcpProperties) {
        return StorageOptions.newBuilder()
                .setProjectId(gcpProperties.projectId())
                .build()
                .getService();
    }
}
