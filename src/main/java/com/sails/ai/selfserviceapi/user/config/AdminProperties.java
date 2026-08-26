package com.sails.ai.selfserviceapi.user.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "admin")
public record AdminProperties(List<String> emails) {

    public AdminProperties {
        emails = emails == null ? List.of() : emails;
    }

    public boolean isAdminEmail(String email) {
        return emails.stream()
                .map(String::trim)
                .filter(e -> !e.isBlank())
                .anyMatch(e -> e.equalsIgnoreCase(email));
    }
}
