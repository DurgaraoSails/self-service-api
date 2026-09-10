package com.sails.ai.selfserviceapi.auth.microsoft;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sails.ai.selfserviceapi.common.exception.ApiException;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class MicrosoftIdentityClient {
    private final MicrosoftSsoProperties properties;
    private final RestClient rest;
    private volatile JwtDecoder decoder;

    @org.springframework.beans.factory.annotation.Autowired
    public MicrosoftIdentityClient(MicrosoftSsoProperties properties) {
        this.properties = properties;
        var factory = new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofSeconds(15));
        this.rest = RestClient.builder().requestFactory(factory).build();
    }

    MicrosoftIdentityClient(MicrosoftSsoProperties properties, RestClient rest, JwtDecoder decoder) {
        this.properties = properties;
        this.rest = rest;
        this.decoder = decoder;
    }

    record Tokens(@JsonProperty("id_token") String idToken, @JsonProperty("access_token") String accessToken) {
        @Override public String toString() { return "Tokens[REDACTED]"; }
    }
    public record Profile(String id, String userType, String mail, String userPrincipalName,
                          String givenName, String surname, String displayName, String jobTitle, String country) {}
    public record Identity(String tenantId, String objectId, String email, Profile profile) {}

    public String authorizationUrl(String email, String challenge, MicrosoftAuthorizationStore.Pending pending) {
        return UriComponentsBuilder.fromUriString(properties.authority() + "/oauth2/v2.0/authorize")
                .queryParam("client_id", properties.clientId()).queryParam("response_type", "code")
                .queryParam("response_mode", "query").queryParam("redirect_uri", properties.redirectUri())
                .queryParam("scope", "openid profile email https://graph.microsoft.com/User.Read")
                .queryParam("state", pending.state()).queryParam("nonce", pending.nonce())
                .queryParam("code_challenge", challenge).queryParam("code_challenge_method", "S256")
                .queryParam("login_hint", EmployeeAccess.normalize(email)).build().encode().toUriString();
    }

    public Identity authenticate(String code, String verifier, String nonce) {
        try {
            var form = new LinkedMultiValueMap<String, String>();
            form.add("client_id", properties.clientId());
            form.add("client_secret", properties.clientSecret());
            form.add("grant_type", "authorization_code");
            form.add("redirect_uri", properties.redirectUri());
            form.add("code", code);
            form.add("code_verifier", verifier);
            Tokens tokens = rest.post().uri(properties.authority() + "/oauth2/v2.0/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(Tokens.class);
            if (tokens == null || tokens.idToken() == null || tokens.accessToken() == null) throw invalid();
            Jwt jwt = decoder().decode(tokens.idToken());
            if (!Objects.equals(nonce, jwt.getClaimAsString("nonce"))
                    || !Objects.equals(properties.tenantId(), jwt.getClaimAsString("tid"))
                    || !jwt.getAudience().contains(properties.clientId())
                    || !Objects.equals(properties.authority() + "/v2.0", jwt.getClaimAsString("iss"))
                    || jwt.getExpiresAt() == null || jwt.getIssuedAt() == null
                    || (jwt.hasClaim("azp") && !properties.clientId().equals(jwt.getClaimAsString("azp")))) throw invalid();
            String oid = UUID.fromString(jwt.getClaimAsString("oid")).toString();
            Profile profile = rest.get().uri("https://graph.microsoft.com/v1.0/me?$select=id,userType,mail,userPrincipalName,givenName,surname,displayName,jobTitle,country")
                    .headers(headers -> headers.setBearerAuth(tokens.accessToken())).retrieve().body(Profile.class);
            if (profile == null || !oid.equalsIgnoreCase(profile.id()) || !"Member".equals(profile.userType())) throw invalid();
            String email = EmployeeAccess.normalize(profile.mail() == null || profile.mail().isBlank()
                    ? profile.userPrincipalName() : profile.mail());
            if (!EmployeeAccess.companyEmail(email) || email.length() > 255) throw invalid();
            return new Identity(properties.tenantId(), oid, email, profile);
        } catch (ApiException ex) {
            throw ex;
        } catch (org.springframework.web.client.RestClientException ex) {
            // Provider response bodies may contain tokens or personal data. Never forward/log them.
            throw new ApiException(HttpStatus.BAD_GATEWAY, "MICROSOFT_SIGN_IN_FAILED",
                    "Microsoft sign-in could not be completed. Please start again or contact your administrator.");
        } catch (RuntimeException ex) {
            throw invalid();
        }
    }

    private JwtDecoder decoder() {
        if (decoder == null) {
            synchronized (this) {
                if (decoder == null) {
                    var nimbus = NimbusJwtDecoder.withJwkSetUri(properties.authority() + "/discovery/v2.0/keys")
                            .restOperations(new org.springframework.web.client.RestTemplate(restFactory())).build();
                    nimbus.setJwtValidator(new DelegatingOAuth2TokenValidator<Jwt>(
                            JwtValidators.createDefaultWithIssuer(properties.authority() + "/v2.0"),
                            jwt -> jwt.getAudience().contains(properties.clientId())
                                    ? OAuth2TokenValidatorResult.success()
                                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"))));
                    decoder = nimbus;
                }
            }
        }
        return decoder;
    }

    private static JdkClientHttpRequestFactory restFactory() {
        var factory = new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofSeconds(15));
        return factory;
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.FORBIDDEN, "MICROSOFT_IDENTITY_REJECTED",
                "Sign in with an eligible Sails company member account.");
    }
}
