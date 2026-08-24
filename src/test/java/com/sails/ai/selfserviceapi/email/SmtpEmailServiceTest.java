package com.sails.ai.selfserviceapi.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import com.sails.ai.selfserviceapi.email.config.AppMailProperties;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.IContext;

class SmtpEmailServiceTest {

    private JavaMailSender mailSender;
    private TemplateEngine templateEngine;
    private SmtpEmailService emailService;

    @BeforeEach
    void setUp() {
        mailSender = Mockito.mock(JavaMailSender.class);
        templateEngine = Mockito.mock(TemplateEngine.class);
        AppMailProperties properties = new AppMailProperties("no-reply@example.com", "Self Service");
        emailService = new SmtpEmailService(mailSender, properties, templateEngine);
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

    @Test
    void rendersTemplateAndSendsAsHtmlWithInlineLogo() throws Exception {
        MimeMessage mimeMessage = new MimeMessage((Session) null);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        when(templateEngine.process(eq("email/otp-verification"), any(IContext.class)))
                .thenReturn("<html><body><p>Your code is <strong>123456</strong>.</p></body></html>");

        emailService.sendTemplate("user@example.com", "Your verification code", "otp-verification",
                Map.of("code", "123456", "expiryMinutes", 10));

        verify(mailSender).send(mimeMessage);
        mimeMessage.saveChanges();
        assertThat(mimeMessage.getContentType()).contains("multipart");
    }

    @Test
    void wrapsTemplateSendFailuresAsApiException() {
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));
        when(templateEngine.process(eq("email/otp-verification"), any(IContext.class)))
                .thenReturn("<html><body>code</body></html>");
        doThrow(new MailSendException("boom")).when(mailSender).send(any(MimeMessage.class));

        assertThatThrownBy(() -> emailService.sendTemplate("user@example.com", "Subject", "otp-verification",
                Map.of("code", "123456", "expiryMinutes", 10)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("EMAIL_SEND_FAILED");
    }
}
