package com.sails.ai.selfserviceapi.support.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sails.ai.selfserviceapi.email.EmailService;
import com.sails.ai.selfserviceapi.support.config.SupportProperties;
import com.sails.ai.selfserviceapi.user.entity.User;
import com.sails.ai.selfserviceapi.user.service.UserService;
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
        SupportProperties properties = new SupportProperties("sales@example.com");
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
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).send(toCaptor.capture(), subjectCaptor.capture(), bodyCaptor.capture());

        assertThat(toCaptor.getValue()).isEqualTo("sales@example.com");
        assertThat(subjectCaptor.getValue()).contains("Acme Corp");
        assertThat(bodyCaptor.getValue())
                .contains("Jane Doe")
                .contains("jane.doe@example.com")
                .contains("Acme Corp")
                .contains("Engineering Manager")
                .contains("United States")
                .contains("We'd like a demo.");
    }

    @Test
    void omitsMessageSectionWhenNoMessageProvided() {
        User user = new User();
        user.setFirstName("Jane");
        user.setLastName("Doe");
        user.setEmail("jane.doe@example.com");
        user.setCompanyName("Acme Corp");
        when(userService.getById(anyString())).thenReturn(user);

        supportService.contactSales("user-1", null);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).send(anyString(), anyString(), bodyCaptor.capture());
        assertThat(bodyCaptor.getValue()).doesNotContain("Message:");
    }
}
