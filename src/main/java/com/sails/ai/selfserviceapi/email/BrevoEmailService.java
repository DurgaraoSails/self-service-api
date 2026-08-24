package com.sails.ai.selfserviceapi.email;

import com.sails.ai.selfserviceapi.email.config.AppMailProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class BrevoEmailService  implements EmailService {
    private final RestClient brevoRestClient;
    private final AppMailProperties mailProperties;


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
}
