package com.sails.ai.selfserviceapi.config;

import com.sails.ai.selfserviceapi.security.JwtProperties;
import com.sails.ai.selfserviceapi.security.PortalAudienceValidator;
import com.sails.ai.selfserviceapi.security.RequirePocAudienceValidator;
import com.sails.ai.selfserviceapi.security.TrialAuthorizationManager;
import com.sails.ai.selfserviceapi.security.TrialExpiredAccessDeniedHandler;
import java.security.interfaces.RSAPublicKey;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthenticatedAuthorizationManager;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Two filter chains, not one, because two classes of token now exist and neither may work where
 * the other is meant to. {@link #pocFilesFilterChain} is matched first ({@code @Order(1)}) and
 * owns exactly {@code /poc-files/**}, requiring a POC-scoped token; {@link #securityFilterChain}
 * is unmatched (falls through to everything else) and keeps requiring a portal access token, as
 * before. A request to {@code /poc-files/**} never reaches the second chain, and nothing else can
 * reach the first — the split itself, not just the two decoders inside it, is what keeps the two
 * token classes apart.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
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

    /**
     * The POC-facing surface. Trial-gated the same way the portal is — the POC token carries
     * {@code trialEndDate} precisely so this can reuse {@link TrialAuthorizationManager} rather
     * than growing a second, separately-maintained expiry rule for a token that can outlive a
     * trial ending mid-session. CORS is open the same as the rest of the API
     * ({@link CorsConfig}): a POC's own browser code calls this directly, by design (see
     * poc-hosting-architecture.md), and that is safe only because this API uses no cookies
     * anywhere, so an open origin list carries no CSRF exposure.
     */
    @Bean
    @Order(1)
    SecurityFilterChain pocFilesFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/poc-files/**")
                .cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
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
                                .decoder(pocFilesJwtDecoder())
                                .jwtAuthenticationConverter(jwtAuthenticationConverter())
                        )
                );

        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/actuator/**",
                                "/swagger-ui/**",
                                "/v3/api-docs/**",
                                "/public/**",
                                "/auth/**",
                                // A public key is not a credential, and POC backends in other
                                // stacks cannot verify a token without it. Listed here rather than
                                // only marked `security: []` in OpenAPI: that documents intent
                                // while Spring Security enforces its own anyRequest() fallback,
                                // a mismatch that has broken a public route in this repo before.
                                "/.well-known/jwks.json"
                        )
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/pocs")
                        .permitAll()
                        // Lets POC backends in any stack verify tokens this API issues without
                        // holding the private key. Must be listed here, not just in OpenAPI's
                        // `security: []` — that documents intent but doesn't bypass this filter.
                        .requestMatchers(HttpMethod.GET, "/.well-known/jwks.json")
                        .permitAll()
                        // Pipeline-facing endpoints. permitAll only bypasses the JWT filter —
                        // each one still verifies the X-Pipeline-Webhook-Secret header itself.
                        .requestMatchers(HttpMethod.POST, "/pocs/deployments/*/status")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/pocs/source-repositories")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/pocs/upstream-commits")
                        .permitAll()
                        .requestMatchers("/api/v1/**")
                        .authenticated()
                        .requestMatchers("/users/me", "/users/me/trial/extension-request", "/support/contact-sales")
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

    /**
     * Validates the portal's own access tokens. POC-scoped tokens are signed by the same key and
     * carry the same issuer, so the audience check is what keeps them out — and it belongs here,
     * at decode time, rather than in an authorization rule: a POC token should fail to
     * authenticate at all, not authenticate and then be denied.
     */
    private JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(jwtPublicKey).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<Jwt>(
                JwtValidators.createDefaultWithIssuer(jwtProperties.issuer()),
                new PortalAudienceValidator()));
        return decoder;
    }

    /**
     * The mirror image: requires the {@code poc:} audience this chain exists to serve, so a
     * portal access token — same key, same issuer — fails to authenticate here rather than
     * reaching an endpoint that takes no user or POC parameter to check it against.
     */
    private JwtDecoder pocFilesJwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(jwtPublicKey).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<Jwt>(
                JwtValidators.createDefaultWithIssuer(jwtProperties.issuer()),
                new RequirePocAudienceValidator()));
        return decoder;
    }

    /**
     * Shared by both chains. A POC token carries no {@code roles} claim, so this simply produces
     * no authorities for one — harmless, since nothing on the POC-facing surface is role-gated.
     */
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();
        authoritiesConverter.setAuthoritiesClaimName("roles");
        authoritiesConverter.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);
        return converter;
    }
}
