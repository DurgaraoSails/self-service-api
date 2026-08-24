package com.sails.ai.selfserviceapi.support.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sails.ai.selfserviceapi.email.EmailService;
import com.sails.ai.selfserviceapi.support.config.SupportProperties;
import com.sails.ai.selfserviceapi.user.entity.User;
import com.sails.ai.selfserviceapi.user.service.UserService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class SupportServiceTest {

    private UserService userService;
    private EmailService emailService;
    private SupportService supportService;

    @BeforeEach
    void setUp() {
        userService = Mockito.mock(UserService.class);
        emailService = Mockito.mock(EmailService.class);
        SupportProperties properties = new SupportProperties("no.reply.sails.poc@gmail.com");
        supportService = new SupportService(userService, emailService, properties);
    }

    @Test
    void emailsSalesWithTheUsersProfileAndMessage() {
        User user = new User();
        user.setFirstName("Jane");
        user.setLastName("Doe");
        user.setEmail("jane.doe@example.com");
        user.setCompanyName("Acme Corp");
        user.setJobTitle("Engineering Manager");
        user.setCountry("United States");
        when(userService.getById("user-1")).thenReturn(user);

        supportService.contactSales("user-1", "We'd like a demo.");

        ArgumentCaptor<String> toCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> subjectCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> templateCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> variablesCaptor = ArgumentCaptor.forClass(Map.class);
        verify(emailService).sendTemplate(toCaptor.capture(), subjectCaptor.capture(), templateCaptor.capture(), variablesCaptor.capture());

        assertThat(toCaptor.getValue()).isEqualTo("no.reply.sails.poc@gmail.com");
        assertThat(subjectCaptor.getValue()).contains("Acme Corp");
        assertThat(templateCaptor.getValue()).isEqualTo("contact-sales");
        assertThat(variablesCaptor.getValue())
                .containsEntry("fullName", "Jane Doe")
                .containsEntry("email", "jane.doe@example.com")
                .containsEntry("companyName", "Acme Corp")
                .containsEntry("jobTitle", "Engineering Manager")
                .containsEntry("country", "United States")
                .containsEntry("message", "We'd like a demo.");
    }

    @Test
    void omitsMessageVariableWhenNoMessageProvided() {
        User user = new User();
        user.setFirstName("Jane");
        user.setLastName("Doe");
        user.setEmail("jane.doe@example.com");
        user.setCompanyName("Acme Corp");
        when(userService.getById(anyString())).thenReturn(user);

        supportService.contactSales("user-1", null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> variablesCaptor = ArgumentCaptor.forClass(Map.class);
        verify(emailService).sendTemplate(anyString(), anyString(), anyString(), variablesCaptor.capture());
        assertThat(variablesCaptor.getValue()).doesNotContainKey("message");
    }
}
