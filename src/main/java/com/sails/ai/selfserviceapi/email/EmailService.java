package com.sails.ai.selfserviceapi.email;

import java.util.Map;

public interface EmailService {

    void send(String to, String subject, String body);

    /**
     * Renders {@code templateName} (a classpath Thymeleaf template under {@code templates/email/},
     * without the {@code .html} suffix) with {@code variables} and sends it as HTML mail with a
     * plain-text fallback.
     */
    void sendTemplate(String to, String subject, String templateName, Map<String, Object> variables);
}
