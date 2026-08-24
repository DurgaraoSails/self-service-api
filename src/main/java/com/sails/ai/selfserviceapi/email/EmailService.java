package com.sails.ai.selfserviceapi.email;

public interface EmailService {

    void send(String to, String subject, String body);
}
