package com.sails.ai.selfserviceapi.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import com.sails.ai.selfserviceapi.email.config.AppMailProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

class SmtpEmailServiceTest {

    private JavaMailSender mailSender;
    private SmtpEmailService emailService;

    @BeforeEach
    void setUp() {
        mailSender = Mockito.mock(JavaMailSender.class);
        AppMailProperties properties = new AppMailProperties("no-reply@example.com", "Self Service");
        emailService = new SmtpEmailService(mailSender, properties);
    }

    @Test
    void sendsAMessageWithTheConfiguredFromAddress() {
        emailService.send("user@example.com", "Subject", "Body");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());

        SimpleMailMessage sent = captor.getValue();
        assertThat(sent.getTo()).containsExactly("user@example.com");
        assertThat(sent.getSubject()).isEqualTo("Subject");
        assertThat(sent.getText()).isEqualTo("Body");
        assertThat(sent.getFrom()).contains("no-reply@example.com").contains("Self Service");
    }

    @Test
    void wrapsMailExceptionsAsApiException() {
        doThrow(new MailSendException("boom")).when(mailSender).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> emailService.send("user@example.com", "Subject", "Body"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("EMAIL_SEND_FAILED");
    }
}
