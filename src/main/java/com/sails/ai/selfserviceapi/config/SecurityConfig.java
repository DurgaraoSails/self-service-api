package com.sails.ai.selfserviceapi.config;

import com.sails.ai.selfserviceapi.security.JwtProperties;
import com.sails.ai.selfserviceapi.security.TrialAuthorizationManager;
import com.sails.ai.selfserviceapi.security.TrialExpiredAccessDeniedHandler;
import java.security.interfaces.RSAPublicKey;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authorization.AuthenticatedAuthorizationManager;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final RSAPublicKey jwtPublicKey;
    private final JwtProperties jwtProperties;
    private final TrialAuthorizationManager trialAuthorizationManager;
    private final TrialExpiredAccessDeniedHandler trialExpiredAccessDeniedHandler;

    public SecurityConfig(RSAPublicKey jwtPublicKey,
                           JwtProperties jwtProperties,
                           TrialAuthorizationManager trialAuthorizationManager,
                           TrialExpiredAccessDeniedHandler trialExpiredAccessDeniedHandler) {
        this.jwtPublicKey = jwtPublicKey;
        this.jwtProperties = jwtProperties;
        this.trialAuthorizationManager = trialAuthorizationManager;
        this.trialExpiredAccessDeniedHandler = trialExpiredAccessDeniedHandler;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/actuator/health",
                                "/swagger-ui/**",
                                "/v3/api-docs/**",
                                "/public/**",
                                "/auth/**"
                        )
                        .permitAll()
                        .requestMatchers("/api/v1/**")
                        .authenticated()
                        .anyRequest()
                        .access(AuthorizationManagers.allOf(
                                AuthenticatedAuthorizationManager.authenticated(),
                                trialAuthorizationManager
                        ))
                )
                .exceptionHandling(exceptionHandling ->
                        exceptionHandling.accessDeniedHandler(trialExpiredAccessDeniedHandler))
                .oauth2ResourceServer(oauth2 ->
                        oauth2.jwt(jwt -> jwt
                                .decoder(jwtDecoder())
                                .jwtAuthenticationConverter(jwtAuthenticationConverter())
                        )
                );

        return http.build();
    }

    private JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(jwtPublicKey).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(jwtProperties.issuer()));
        return decoder;
    }

    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();
        authoritiesConverter.setAuthoritiesClaimName("roles");
        authoritiesConverter.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);
        return converter;
    }
}
