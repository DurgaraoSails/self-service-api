package com.sails.ai.selfserviceapi.file.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.MultipartConfigElement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

/**
 * No servlet container runs in this test suite, so this is the only place the actual bean this
 * feature depends on — the spool threshold sitting strictly above the file-size limit — is
 * checked at all. {@code PocFilesControllerTest}'s multipart requests go through MockMvc's mock
 * dispatcher, which never touches {@link MultipartConfigElement}.
 */
class MultipartUploadConfigTest {

    private final MultipartUploadConfig config = new MultipartUploadConfig();

    @Test
    void fileSizeThresholdSitsStrictlyAboveMaxFileSize() {
        MultipartConfigElement element = config.multipartConfigElement(properties(DataSize.ofMegabytes(10)));

        assertThat(element.getMaxFileSize()).isEqualTo(DataSize.ofMegabytes(10).toBytes());
        assertThat(element.getFileSizeThreshold()).isGreaterThan((int) element.getMaxFileSize());
    }

    /** The relationship must hold for whatever files.max-file-size is configured to, not just 10MB. */
    @Test
    void theRelationshipHoldsForADifferentlyConfiguredLimit() {
        MultipartConfigElement element = config.multipartConfigElement(properties(DataSize.ofMegabytes(2)));

        assertThat(element.getMaxFileSize()).isEqualTo(DataSize.ofMegabytes(2).toBytes());
        assertThat(element.getFileSizeThreshold()).isGreaterThan((int) element.getMaxFileSize());
    }

    @Test
    void maxRequestSizeAllowsHeadroomAboveTheFileItself() {
        MultipartConfigElement element = config.multipartConfigElement(properties(DataSize.ofMegabytes(10)));

        assertThat(element.getMaxRequestSize()).isGreaterThan(element.getMaxFileSize());
    }

    private static FileStorageProperties properties(DataSize maxFileSize) {
        return new FileStorageProperties(
                "local", null, null, maxFileSize, 20, DataSize.ofMegabytes(250), List.of("application/pdf"));
    }
}
