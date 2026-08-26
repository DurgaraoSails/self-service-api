package com.sails.ai.selfserviceapi.user.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class AdminPropertiesTest {

    @Test
    void isAdminEmailMatchesCaseInsensitively() {
        AdminProperties properties = new AdminProperties(List.of("Admin@Example.com"));

        assertThat(properties.isAdminEmail("admin@example.com")).isTrue();
        assertThat(properties.isAdminEmail("ADMIN@EXAMPLE.COM")).isTrue();
    }

    @Test
    void isAdminEmailIsFalseForUnlistedEmail() {
        AdminProperties properties = new AdminProperties(List.of("admin@example.com"));

        assertThat(properties.isAdminEmail("someone-else@example.com")).isFalse();
    }

    @Test
    void isAdminEmailIsFalseWhenNoEmailsConfigured() {
        AdminProperties properties = new AdminProperties(null);

        assertThat(properties.isAdminEmail("admin@example.com")).isFalse();
    }

    @Test
    void isAdminEmailIgnoresBlankEntries() {
        AdminProperties properties = new AdminProperties(List.of("", "  ", "admin@example.com"));

        assertThat(properties.isAdminEmail("admin@example.com")).isTrue();
        assertThat(properties.isAdminEmail("")).isFalse();
    }
}
