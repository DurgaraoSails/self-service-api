package com.sails.ai.selfserviceapi.file.service;

import com.sails.ai.selfserviceapi.file.config.FileStorageProperties;
import com.sails.ai.selfserviceapi.file.exception.UnsupportedFileTypeException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Decides whether an upload is a type this platform accepts, by looking at the bytes rather than
 * the {@code Content-Type} header the client sent — a header is a claim, and an attacker writes it.
 *
 * <p>What this is <em>not</em> is a malware check. The spec bounds that risk structurally instead:
 * files are scoped to a single (user, POC) pair and are never readable by another user, so nothing
 * uploaded here can be distributed to anyone. Nor does it stop a {@code text/plain} upload that
 * happens to contain HTML or SVG — there is no byte signature for "plain text", and the mitigation
 * for that case is the download path's {@code Content-Disposition: attachment} and {@code nosniff},
 * not sniffing.
 */
@Component
public class ContentTypeValidator {

    /** Enough for every signature below, with room for the text heuristic to be worth anything. */
    public static final int SAMPLE_BYTES = 512;

    /**
     * The families this validator can actually verify. Several distinct content types map to one
     * family because their bytes are genuinely identical — see {@link #OOXML}.
     */
    private enum Family {
        PDF, OOXML, PNG, JPEG, TEXT
    }

    private static final byte[] PDF = {'%', 'P', 'D', 'F', '-'};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};

    /**
     * DOCX, XLSX and PPTX are all ZIP containers and share one signature; telling them apart means
     * reading {@code [Content_Types].xml} out of the archive, which is exactly the parsing this
     * platform says it does not do. So the family is verified and the declared subtype within it
     * is taken at its word. The consequence is bounded: a caller can pass a XLSX off as a DOCX,
     * which affects only the POC that then fails to parse it, and never crosses a user boundary.
     */
    private static final byte[] OOXML = {'P', 'K', 0x03, 0x04};

    private static final Map<String, Family> KNOWN_TYPES = Map.of(
            "application/pdf", Family.PDF,
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", Family.OOXML,
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", Family.OOXML,
            "application/vnd.openxmlformats-officedocument.presentationml.presentation", Family.OOXML,
            "image/png", Family.PNG,
            "image/jpeg", Family.JPEG,
            "text/plain", Family.TEXT,
            "text/csv", Family.TEXT
    );

    private final Set<String> allowed;

    public ContentTypeValidator(FileStorageProperties properties) {
        this.allowed = new HashSet<>(properties.allowedContentTypes().stream()
                .map(ContentTypeValidator::normalize)
                .toList());

        // Fail at startup, not at upload time. A type on the allowlist with no signature behind it
        // could only be handled by skipping validation for it, which would quietly turn the
        // allowlist into the very thing it exists to replace: a claim from the client.
        Set<String> unverifiable = new HashSet<>(allowed);
        unverifiable.removeAll(KNOWN_TYPES.keySet());
        if (!unverifiable.isEmpty()) {
            throw new IllegalStateException(
                    "files.allowed-content-types contains types this build cannot verify by content: "
                            + unverifiable + ". Adding a type needs a signature in ContentTypeValidator, "
                            + "not just a configuration entry.");
        }
    }

    /**
     * @param declaredContentType the client's {@code Content-Type}, parameters and all
     * @param leadingBytes        the first {@link #SAMPLE_BYTES} of the upload, or fewer for a
     *                            short file
     * @throws UnsupportedFileTypeException if the type is not accepted, or the bytes disagree
     */
    public void validate(String declaredContentType, byte[] leadingBytes) {
        String type = normalize(declaredContentType);
        if (!allowed.contains(type)) {
            throw UnsupportedFileTypeException.notAllowed(declaredContentType);
        }

        if (!matches(KNOWN_TYPES.get(type), leadingBytes)) {
            throw UnsupportedFileTypeException.contentMismatch(declaredContentType);
        }
    }

    private static boolean matches(Family family, byte[] bytes) {
        return switch (family) {
            case PDF -> startsWith(bytes, PDF);
            case OOXML -> startsWith(bytes, OOXML);
            case PNG -> startsWith(bytes, PNG);
            case JPEG -> startsWith(bytes, JPEG);
            case TEXT -> looksLikeText(bytes);
        };
    }

    private static boolean startsWith(byte[] bytes, byte[] signature) {
        return bytes.length >= signature.length
                && Arrays.equals(bytes, 0, signature.length, signature, 0, signature.length);
    }

    /**
     * Text has no magic number, so this asks the only question that can be answered from bytes
     * alone: is this binary? A NUL byte or a stray control character says yes — the same test
     * every diff tool uses, and enough to stop a renamed PDF or executable arriving as text/csv.
     * An empty file is accepted; the quota layer, not this one, decides whether that is useful.
     */
    private static boolean looksLikeText(byte[] bytes) {
        for (byte b : bytes) {
            if (b == 0) {
                return false;
            }
            boolean isControl = b >= 0 && b < 0x20;
            boolean isAllowedWhitespace = b == '\t' || b == '\n' || b == '\r' || b == 0x0C;
            if (isControl && !isAllowedWhitespace) {
                return false;
            }
        }
        return true;
    }

    /** Drops parameters ({@code text/csv; charset=utf-8}) and case, neither of which is meaning. */
    private static String normalize(String contentType) {
        if (contentType == null) {
            return "";
        }
        int parameterStart = contentType.indexOf(';');
        String base = parameterStart < 0 ? contentType : contentType.substring(0, parameterStart);
        return base.trim().toLowerCase(Locale.ROOT);
    }
}
