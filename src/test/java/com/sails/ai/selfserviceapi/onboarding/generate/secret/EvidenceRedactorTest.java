package com.sails.ai.selfserviceapi.onboarding.generate.secret;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EvidenceRedactorTest {

    @Test
    void redactsAKeyValueLineWhoseKeyLooksSecret() {
        String redacted = EvidenceRedactor.redact("DB_PASSWORD=hunter2verylongvalue1234567890");

        assertThat(redacted).isEqualTo("DB_PASSWORD=<redacted>");
        assertThat(redacted).doesNotContain("hunter2");
    }

    /**
     * A known-prefix token is caught by the token-shape pass first, so the line-based pass sees it
     * already redacted and leaves the more specific "<redacted:token>" marker rather than replacing
     * it with the generic one.
     */
    @Test
    void redactsAYamlStyleKeyValueLineWhoseValueLooksSecret() {
        String redacted = EvidenceRedactor.redact("API_TOKEN: ghp_abcdefghijklmnopqrstuvwxyz0123456789");

        assertThat(redacted).isEqualTo("API_TOKEN: <redacted:token>");
    }

    /** A high-entropy secret value with no recognized prefix is caught only by the line-based pass. */
    @Test
    void redactsAYamlStyleKeyValueLineWithAnUnprefixedHighEntropyValue() {
        String redacted = EvidenceRedactor.redact("API_TOKEN: a1B2c3D4e5F6g7H8i9J0k1L2m3N4");

        assertThat(redacted).isEqualTo("API_TOKEN: <redacted>");
    }

    @Test
    void leavesOrdinaryLinesUntouched() {
        String content = "LOG_LEVEL=info\nfoo: bar\njust some text";

        assertThat(EvidenceRedactor.redact(content)).isEqualTo(content);
    }

    @Test
    void redactsAPemBlockAnywhereNotOnlyInAKeyValueLine() {
        String content = "Here is a key for reference:\n"
                + "-----BEGIN PRIVATE KEY-----\nMIIBVgIBADANBgkq\n-----END PRIVATE KEY-----\nmore text";

        String redacted = EvidenceRedactor.redact(content);

        assertThat(redacted).doesNotContain("MIIBVgIBADANBgkq");
        assertThat(redacted).contains("<redacted:pem>");
    }

    @Test
    void redactsAJwtAnywhereInTheText() {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U";
        String content = "curl -H 'Authorization: Bearer " + jwt + "' https://api.example.com";

        String redacted = EvidenceRedactor.redact(content);

        assertThat(redacted).doesNotContain(jwt);
        assertThat(redacted).contains("<redacted:jwt>");
    }

    @Test
    void redactsAPrefixedTokenAnywhereInTheText() {
        String content = "export GITHUB_TOKEN=ghp_abcdefghijklmnopqrstuvwxyz0123456789 # do not commit";

        String redacted = EvidenceRedactor.redact(content);

        assertThat(redacted).doesNotContain("ghp_abcdefghijklmnopqrstuvwxyz0123456789");
    }

    @Test
    void preservesLineStructureAcrossMultipleLines() {
        String content = "FIRST=1\nDB_PASSWORD=verylongsecretvalue1234567890\nTHIRD=3";

        String redacted = EvidenceRedactor.redact(content);

        assertThat(redacted).isEqualTo("FIRST=1\nDB_PASSWORD=<redacted>\nTHIRD=3");
    }

    @Test
    void handlesNullGracefully() {
        assertThat(EvidenceRedactor.redact(null)).isNull();
    }
}
