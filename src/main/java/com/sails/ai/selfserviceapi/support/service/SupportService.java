package com.sails.ai.selfserviceapi.support.service;

import com.sails.ai.selfserviceapi.email.EmailService;
import com.sails.ai.selfserviceapi.support.config.SupportProperties;
import com.sails.ai.selfserviceapi.user.entity.User;
import com.sails.ai.selfserviceapi.user.service.UserService;
import org.springframework.stereotype.Service;

@Service
public class SupportService {

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
        emailService.send(properties.salesEmail(), buildSubject(user), buildBody(user, message));
    }

    private String buildSubject(User user) {
        return "Contact Sales request from " + user.getCompanyName();
    }

    private String buildBody(User user, String message) {
        StringBuilder body = new StringBuilder();
        body.append("Name: ").append(user.getFirstName()).append(' ').append(user.getLastName()).append('\n');
        body.append("Email: ").append(user.getEmail()).append('\n');
        body.append("Company: ").append(user.getCompanyName()).append('\n');
        if (user.getJobTitle() != null) {
            body.append("Job title: ").append(user.getJobTitle()).append('\n');
        }
        if (user.getCountry() != null) {
            body.append("Country: ").append(user.getCountry()).append('\n');
        }
        if (user.getTrialStartDate() != null) {
            body.append("Trial start: ").append(user.getTrialStartDate()).append('\n');
        }
        if (user.getTrialEndDate() != null) {
            body.append("Trial end: ").append(user.getTrialEndDate()).append('\n');
        }
        if (message != null && !message.isBlank()) {
            body.append("\nMessage:\n").append(message).append('\n');
        }
        return body.toString();
    }
}
