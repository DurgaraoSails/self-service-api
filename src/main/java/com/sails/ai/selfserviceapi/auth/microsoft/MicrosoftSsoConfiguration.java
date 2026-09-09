package com.sails.ai.selfserviceapi.auth.microsoft;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

@Configuration
@PropertySource("classpath:microsoft-sso.properties")
public class MicrosoftSsoConfiguration {}
