package com.sails.ai.selfserviceapi.onboarding.generate.secret;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SecretHeuristicsTest {

    @Test
    void recognizesSecretShapedNames() {
        assertThat(SecretHeuristics.nameLooksSecret("DB_PASSWORD")).isTrue();
        assertThat(SecretHeuristics.nameLooksSecret("API_KEY")).isTrue();
        assertThat(SecretHeuristics.nameLooksSecret("STRIPE_SECRET_KEY")).isTrue();
        assertThat(SecretHeuristics.nameLooksSecret("CLIENT_SECRET")).isTrue();
        assertThat(SecretHeuristics.nameLooksSecret("SIGNING_KEY")).isTrue();
        assertThat(SecretHeuristics.nameLooksSecret("DATABASE_DSN")).isTrue();
        assertThat(SecretHeuristics.nameLooksSecret("CONNECTION_STRING")).isTrue();
        assertThat(SecretHeuristics.nameLooksSecret("LOG_LEVEL")).isFalse();
        assertThat(SecretHeuristics.nameLooksSecret("PORT")).isFalse();
    }

    @Test
    void recognizesKnownTokenPrefixes() {
        assertThat(SecretHeuristics.valueLooksSecret("ghp_abcdefghijklmnopqrstuvwxyz0123456789")).isTrue();
        assertThat(SecretHeuristics.valueLooksSecret("github_pat_11ABCDEF_somethinglong")).isTrue();
        assertThat(SecretHeuristics.valueLooksSecret("glpat-abcdefghijklmnopqrst")).isTrue();
        assertThat(SecretHeuristics.valueLooksSecret("sk-abcdefghijklmnopqrstuvwx")).isTrue();
        assertThat(SecretHeuristics.valueLooksSecret("AKIAABCDEFGHIJKLMNOP")).isTrue();
        assertThat(SecretHeuristics.valueLooksSecret("AIzaSyAbCdEfGhIjKlMnOpQrStUvWxYz012")).isTrue();
    }

    @Test
    void recognizesPemAndJwtShapes() {
        String pem = "-----BEGIN PRIVATE KEY-----\nMIIBVgIBADANBgkq\n-----END PRIVATE KEY-----";
        assertThat(SecretHeuristics.valueLooksSecret(pem)).isTrue();

        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U";
        assertThat(SecretHeuristics.valueLooksSecret(jwt)).isTrue();
    }

    @Test
    void recognizesAUrlCarryingCredentials() {
        assertThat(SecretHeuristics.valueLooksSecret("postgres://myuser:mypassword@db.example.com:5432/app")).isTrue();
    }

    @Test
    void recognizesHighEntropyLiteralsButNotOrdinaryWords() {
        assertThat(SecretHeuristics.valueLooksSecret("a1B2c3D4e5F6g7H8i9J0k1L2m3N4")).isTrue();
        assertThat(SecretHeuristics.valueLooksSecret("info")).isFalse();
        assertThat(SecretHeuristics.valueLooksSecret("production")).isFalse();
        assertThat(SecretHeuristics.valueLooksSecret("http://localhost:3000")).isFalse();
        assertThat(SecretHeuristics.valueLooksSecret("")).isFalse();
        assertThat(SecretHeuristics.valueLooksSecret(null)).isFalse();
    }

    @Test
    void isSecretIsTrueWhenEitherNameOrValueQualifies() {
        assertThat(SecretHeuristics.isSecret("LOG_LEVEL", "ghp_abcdefghijklmnopqrstuvwxyz0123456789")).isTrue();
        assertThat(SecretHeuristics.isSecret("DB_PASSWORD", "anything")).isTrue();
        assertThat(SecretHeuristics.isSecret("LOG_LEVEL", "info")).isFalse();
    }

    @Test
    void findsAssignmentsInFreeText() {
        assertThat(SecretHeuristics.containsSecretAssignment("OPENAI_API_KEY=sk-verylongsecretvalue1234567890")).isTrue();
        assertThat(SecretHeuristics.containsSecretAssignment("LOG_LEVEL=info")).isFalse();
    }
}
