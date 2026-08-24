package com.sails.ai.selfserviceapi.email;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import com.sails.ai.selfserviceapi.email.config.AppMailProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Profile("!local")
@Service
public class SmtpEmailService implements EmailService {

    private final JavaMailSender mailSender;
    private final AppMailProperties mailProperties;

    public SmtpEmailService(JavaMailSender mailSender, AppMailProperties mailProperties) {
        this.mailSender = mailSender;
        this.mailProperties = mailProperties;
    }

    @Override
    public void send(String to, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(String.format("%s <%s>", mailProperties.fromName(), mailProperties.fromAddress()));
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);

        try {
            mailSender.send(message);
        } catch (MailException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "EMAIL_SEND_FAILED", "Failed to send email.");
        }
    }
}
