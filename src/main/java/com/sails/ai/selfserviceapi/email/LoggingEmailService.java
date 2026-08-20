package com.sails.ai.selfserviceapi.email;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

// Placeholder — swap for a real provider once one is chosen; callers only depend on EmailService.
@Slf4j
@Service
public class LoggingEmailService implements EmailService {

    @Override
    public void send(String to, String subject, String body) {
        log.info("Email queued (logging-only): to={} subject=\"{}\"", to, subject);
    }
}
