package com.sails.ai.selfserviceapi.file.config;

import jakarta.servlet.MultipartConfigElement;
import org.springframework.boot.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

/**
 * Derives Spring's multipart limits from {@link FileStorageProperties} instead of setting them
 * independently in YAML, because the spec requires the spool threshold to sit strictly above the
 * per-file ceiling — computing it here is what guarantees that relationship holds for whatever
 * {@code files.max-file-size} is configured to, rather than two numbers that happen to agree
 * today and can silently drift apart the next time either one is edited.
 *
 * <p>Overriding Boot's auto-configured {@link MultipartConfigElement} bean also means a request
 * over the limit is rejected while Spring is still parsing the multipart body — before
 * {@link com.sails.ai.selfserviceapi.file.service.FileService} runs its own size check, which
 * exists for defense in depth (and for testability without a servlet container) rather than as
 * the primary enforcement.
 */
@Configuration
public class MultipartUploadConfig {

    /**
     * Headroom for multipart framing (boundaries, the field's own headers) around the file
     * itself, not another file's worth of margin.
     */
    private static final DataSize OVERHEAD = DataSize.ofKilobytes(64);

    @Bean
    MultipartConfigElement multipartConfigElement(FileStorageProperties properties) {
        long maxFileSizeBytes = properties.maxFileSizeBytes();
        DataSize requestCeiling = DataSize.ofBytes(maxFileSizeBytes + OVERHEAD.toBytes());

        MultipartConfigFactory factory = new MultipartConfigFactory();
        factory.setMaxFileSize(DataSize.ofBytes(maxFileSizeBytes));
        factory.setMaxRequestSize(requestCeiling);
        // Strictly above maxFileSize: a file at the limit is written straight through rather than
        // spooled to Cloud Run's memory-backed filesystem.
        factory.setFileSizeThreshold(requestCeiling);
        return factory.createMultipartConfig();
    }
}
