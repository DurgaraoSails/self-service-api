package com.sails.ai.selfserviceapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SelfServiceApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(SelfServiceApiApplication.class, args);
	}

}
