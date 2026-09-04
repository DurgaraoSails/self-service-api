package com.sails.ai.selfserviceapi.file.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sails.ai.selfserviceapi.file.config.FileStorageProperties;
import com.sails.ai.selfserviceapi.file.exception.UnsupportedFileTypeException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class ContentTypeValidatorTest {

    private static final List<String> ALLOWED = List.of(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "text/plain",
            "text/csv",
            "image/png",
            "image/jpeg");

    private final ContentTypeValidator validator = new ContentTypeValidator(properties(ALLOWED));

    @Test
    void acceptsAPdfWhoseBytesAreAPdf() {
        assertThatCode(() -> validator.validate("application/pdf", bytes("%PDF-1.7\nstuff")))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsAContentTypeCarryingParameters() {
        assertThatCode(() -> validator.validate("text/csv; charset=utf-8", bytes("a,b,c\n1,2,3")))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsAnOoxmlDocumentByItsZipContainer() {
        assertThatCode(() -> validator.validate(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                new byte[]{'P', 'K', 0x03, 0x04, 0x14, 0x00}))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsATypeThatIsNotOnTheAllowlist() {
        assertThatThrownBy(() -> validator.validate("image/svg+xml", bytes("<svg/>")))
                .isInstanceOf(UnsupportedFileTypeException.class)
                .hasMessageContaining("not accepted");
    }

    /** The whole point: the header is a claim, and this is what stops it being taken as fact. */
    @Test
    void rejectsAPngRenamedAsAPdf() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x01};
        assertThatThrownBy(() -> validator.validate("application/pdf", png))
                .isInstanceOf(UnsupportedFileTypeException.class)
                .hasMessageContaining("do not match");
    }

    @Test
    void rejectsBinaryContentDeclaredAsText() {
        byte[] binary = {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0x00, 0x01};
        assertThatThrownBy(() -> validator.validate("text/csv", binary))
                .isInstanceOf(UnsupportedFileTypeException.class);
    }

    @Test
    void acceptsTextContainingTabsAndNewlines() {
        assertThatCode(() -> validator.validate("text/plain", bytes("line one\r\n\tindented\f\n")))
                .doesNotThrowAnyException();
    }

    /**
     * Not an oversight. There is no byte signature for "plain text", so an HTML file declared as
     * text/plain is indistinguishable from any other text — which is exactly why the download path
     * sets Content-Disposition: attachment and nosniff rather than relying on this check.
     */
    @Test
    void acceptsHtmlDeclaredAsPlainText() {
        assertThatCode(() -> validator.validate("text/plain", bytes("<script>alert(1)</script>")))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsAFileTooShortToCarryItsSignature() {
        assertThatThrownBy(() -> validator.validate("image/png", new byte[]{(byte) 0x89, 'P'}))
                .isInstanceOf(UnsupportedFileTypeException.class);
    }

    @Test
    void refusesToStartWithAnAllowlistedTypeItCannotVerify() {
        assertThatThrownBy(() -> new ContentTypeValidator(properties(List.of("application/zip"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("application/zip");
    }

    @Test
    void treatsAnEmptyAllowlistAsRejectingEverything() {
        ContentTypeValidator empty = new ContentTypeValidator(properties(List.of()));
        assertThatThrownBy(() -> empty.validate("application/pdf", bytes("%PDF-1.7")))
                .isInstanceOf(UnsupportedFileTypeException.class);
    }

    @Test
    void samplesEnoughBytesForEverySignature() {
        assertThat(ContentTypeValidator.SAMPLE_BYTES).isGreaterThan(8);
    }

    private static byte[] bytes(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    private static FileStorageProperties properties(List<String> allowedContentTypes) {
        return new FileStorageProperties(
                "local", null, null, DataSize.ofMegabytes(10), 20, DataSize.ofMegabytes(250), allowedContentTypes);
    }
}
