package com.sails.ai.selfserviceapi.support.service;

import com.sails.ai.selfserviceapi.email.EmailService;
import com.sails.ai.selfserviceapi.support.config.SupportProperties;
import com.sails.ai.selfserviceapi.user.entity.User;
import com.sails.ai.selfserviceapi.user.service.UserService;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class SupportService {

    private static final DateTimeFormatter TRIAL_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private final UserService userService;
    private final EmailService emailService;
    private final SupportProperties properties;

    public SupportService(UserService userService, EmailService emailService, SupportProperties properties) {
        this.userService = userService;
        this.emailService = emailService;
        this.properties = properties;
    }

    public void contactSales(String userId, String message) {
        User user = userService.getById(userId);
        emailService.sendTemplate(properties.salesEmail(), buildSubject(user), "contact-sales", buildVariables(user, message));
    }

    /** Notifies sales that a user has asked to extend their trial, with their reason. */
    public void notifyTrialExtensionRequest(String userId, String note) {
        User user = userService.getById(userId);
        Map<String, Object> variables = buildVariables(user, null);
        variables.put("note", note);
        emailService.sendTemplate(properties.salesEmail(), "Trial extension request from " + user.getCompanyName(),
                "trial-extension-request", variables);
    }

    private String buildSubject(User user) {
        return "Contact Sales request from " + user.getCompanyName();
    }

    private Map<String, Object> buildVariables(User user, String message) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("fullName", user.getFirstName() + " " + user.getLastName());
        variables.put("email", user.getEmail());
        variables.put("companyName", user.getCompanyName());
        variables.put("jobTitle", user.getJobTitle());
        variables.put("country", user.getCountry());
        if (user.getTrialStartDate() != null) {
            variables.put("trialStartDate", TRIAL_DATE_FORMAT.format(user.getTrialStartDate()));
        }
        if (user.getTrialEndDate() != null) {
            variables.put("trialEndDate", TRIAL_DATE_FORMAT.format(user.getTrialEndDate()));
        }
        if (message != null && !message.isBlank()) {
            variables.put("message", message);
        }
        return variables;
    }
}
