package com.sails.ai.selfserviceapi.email;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import com.sails.ai.selfserviceapi.email.config.AppMailProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class BrevoEmailService  implements EmailService {
    private static final String LOGO_URL = "https://yourdomain.com/static/logo.png";
    private final RestClient brevoRestClient;
    private final AppMailProperties mailProperties;
    private final TemplateEngine templateEngine;


    @Override
    public void send(String to, String subject, String body) {
        Map<String, Object> payload = Map.of(
                "sender", Map.of("name", mailProperties.fromName(), "email", mailProperties.fromAddress()),
                "to", List.of(Map.of("email", to)),
                "subject", subject,
                "htmlContent", body
        );

        String response = brevoRestClient.post()
                .uri("/smtp/email")
                .body(payload)
                .retrieve()
                .body(String.class);
    }

    @Override
    public void sendTemplate(String to, String subject, String templateName, Map<String, Object> variables) {
        Context context = new Context();
        context.setVariables(variables);
        context.setVariable("logoUrl", LOGO_URL); // template now uses ${logoUrl} instead of cid:logoContentId
        String html = templateEngine.process("email/" + templateName, context);

        Map<String, Object> payload = Map.of(
                "sender", Map.of(
                        "name", mailProperties.fromName(),
                        "email", mailProperties.fromAddress()
                ),
                "to", List.of(Map.of("email", to)),
                "subject", subject,
                "htmlContent", html,
                "textContent", toPlainText(html)
        );

        try {
            brevoRestClient.post()
                    .uri("/smtp/email")
                    .body(payload)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "EMAIL_SEND_FAILED", "Failed to send email.");
        }
    }

    /**
     * Derives a plain-text alternative from rendered template HTML, for mail clients that don't
     * render HTML and for spam-filter deliverability scoring — not a general-purpose HTML sanitizer.
     */
    private String toPlainText(String html) {
        String withoutTags = html
                .replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", "")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</p>", "\n\n")
                .replaceAll("<[^>]+>", "");
        return withoutTags
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }
}
