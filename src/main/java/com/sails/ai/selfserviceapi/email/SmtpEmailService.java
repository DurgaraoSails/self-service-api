package com.sails.ai.selfserviceapi.email;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import com.sails.ai.selfserviceapi.email.config.AppMailProperties;
import jakarta.mail.internet.MimeMessage;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

public class SmtpEmailService implements EmailService {

    private static final String LOGO_RESOURCE_PATH = "templates/email/sails-logo.png";
    private static final String LOGO_CONTENT_ID = "sailsLogo";

    private final JavaMailSender mailSender;
    private final AppMailProperties mailProperties;
    private final TemplateEngine templateEngine;

    public SmtpEmailService(JavaMailSender mailSender, AppMailProperties mailProperties, TemplateEngine templateEngine) {
        this.mailSender = mailSender;
        this.mailProperties = mailProperties;
        this.templateEngine = templateEngine;
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

    @Override
    public void sendTemplate(String to, String subject, String templateName, Map<String, Object> variables) {
        Context context = new Context();
        context.setVariables(variables);
        context.setVariable("logoContentId", LOGO_CONTENT_ID);
        String html = templateEngine.process("email/" + templateName, context);

        try {
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED, "UTF-8");
            helper.setFrom(String.format("%s <%s>", mailProperties.fromName(), mailProperties.fromAddress()));
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(toPlainText(html), html);
            helper.addInline(LOGO_CONTENT_ID, new ClassPathResource(LOGO_RESOURCE_PATH), "image/png");

            mailSender.send(mimeMessage);
        } catch (jakarta.mail.MessagingException | MailException e) {
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
