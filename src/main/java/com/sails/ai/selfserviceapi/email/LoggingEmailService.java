package com.sails.ai.selfserviceapi.email;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

// Local-dev-only stand-in for SmtpEmailService — avoids requiring real SMTP creds to run locally.
@Slf4j
@Profile("local")
@Service
public class LoggingEmailService implements EmailService {

    private final Environment environment;

    public LoggingEmailService(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void send(String to, String subject, String body) {
        if (environment.matchesProfiles("local")) {
            // Body (contains the raw OTP/verification code) is only ever logged in local dev, never otherwise.
            log.info("Email (local dev): to={} subject=\"{}\" body=\"{}\"", to, subject, body);
        } else {
            log.info("Email queued (logging-only): to={} subject=\"{}\"", to, subject);
        }
    }
}
