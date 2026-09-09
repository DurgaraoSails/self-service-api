package com.sails.ai.selfserviceapi.auth.microsoft;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import io.jsonwebtoken.Jwts;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class MicrosoftIdentityClientTest {
    static final String TENANT = "10000000-0000-0000-0000-000000000001";
    static final String CLIENT = "20000000-0000-0000-0000-000000000002";
    static final String OBJECT = "30000000-0000-0000-0000-000000000003";
    static final MicrosoftSsoProperties CONFIG = new MicrosoftSsoProperties(true, TENANT, CLIENT, "test-only-secret", "http://localhost:4200/auth/microsoft/callback");
    private KeyPair keys;
    private MockRestServiceServer server;
    private MicrosoftIdentityClient client;

    @BeforeEach void setUp() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keys = generator.generateKeyPair();
        var decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) keys.getPublic()).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(CONFIG.authority() + "/v2.0"));
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new MicrosoftIdentityClient(CONFIG, builder.build(), decoder);
    }

    private String token(Consumer<io.jsonwebtoken.JwtBuilder> change) {
        var builder = Jwts.builder().issuer(CONFIG.authority() + "/v2.0").subject("subject")
                .audience().add(CLIENT).and().issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(300))).claim("nonce", "nonce")
                .claim("tid", TENANT).claim("oid", OBJECT);
        change.accept(builder);
        return builder.signWith(keys.getPrivate()).compact();
    }

    private void expectToken(String token) {
        server.expect(requestTo(CONFIG.authority() + "/oauth2/v2.0/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("code_verifier=verifier")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("client_secret=test-only-secret")))
                .andRespond(withSuccess("{\"id_token\":\"" + token + "\",\"access_token\":\"graph-token\"}", MediaType.APPLICATION_JSON));
    }

    private void expectProfile(String type, String mail, String upn, String object) {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://graph.microsoft.com/v1.0/me?")))
                .andExpect(header("Authorization", "Bearer graph-token"))
                .andRespond(withSuccess("{\"id\":\"" + object + "\",\"userType\":\"" + type + "\",\"mail\":"
                        + (mail == null ? "null" : "\"" + mail + "\"") + ",\"userPrincipalName\":\"" + upn + "\",\"displayName\":\"Jane Doe\"}", MediaType.APPLICATION_JSON));
    }

    @Test void authenticatesAndNormalizesDirectoryMail() {
        expectToken(token(b -> {}));
        expectProfile("Member", "Jane@SAILSSOFTWARE.COM", "different@other.com", OBJECT);
        var identity = client.authenticate("code", "verifier", "nonce");
        assertThat(identity.email()).isEqualTo("jane@sailssoftware.com");
        assertThat(identity.objectId()).isEqualTo(OBJECT);
        server.verify();
    }

    @Test void fallsBackToUpnOnlyWhenMailMissing() {
        expectToken(token(b -> {}));
        expectProfile("Member", null, "jane@sailssoftware.com", OBJECT);
        assertThat(client.authenticate("code", "verifier", "nonce").email()).isEqualTo("jane@sailssoftware.com");
    }

    @Test void rejectsGuest() {
        expectToken(token(b -> {})); expectProfile("Guest", "jane@sailssoftware.com", "jane@sailssoftware.com", OBJECT);
        assertThatThrownBy(() -> client.authenticate("code", "verifier", "nonce")).isInstanceOf(ApiException.class);
    }
    @Test void rejectsWrongGraphObject() {
        expectToken(token(b -> {})); expectProfile("Member", "jane@sailssoftware.com", "jane@sailssoftware.com", CLIENT);
        assertThatThrownBy(() -> client.authenticate("code", "verifier", "nonce")).isInstanceOf(ApiException.class);
    }
    @Test void rejectsExternalMailEvenWhenUpnIsCompanyAddress() {
        expectToken(token(b -> {})); expectProfile("Member", "jane@other.com", "jane@sailssoftware.com", OBJECT);
        assertThatThrownBy(() -> client.authenticate("code", "verifier", "nonce")).isInstanceOf(ApiException.class);
    }
    @Test void rejectsSubdomain() {
        expectToken(token(b -> {})); expectProfile("Member", "jane@evil.sailssoftware.com", "jane@sailssoftware.com", OBJECT);
        assertThatThrownBy(() -> client.authenticate("code", "verifier", "nonce")).isInstanceOf(ApiException.class);
    }
    @Test void rejectsWrongTenantBeforeReadingGraph() { rejectToken(b -> b.claim("tid", CLIENT)); }
    @Test void rejectsWrongAudienceBeforeReadingGraph() { rejectToken(b -> b.audience().clear().add("other")); }
    @Test void rejectsWrongIssuerBeforeReadingGraph() { rejectToken(b -> b.issuer("https://example.com")); }
    @Test void rejectsWrongNonceBeforeReadingGraph() { rejectToken(b -> b.claim("nonce", "other")); }
    @Test void rejectsExpiredTokenBeforeReadingGraph() { rejectToken(b -> b.expiration(Date.from(Instant.now().minusSeconds(120)))); }
    @Test void rejectsMissingExpiryBeforeReadingGraph() { rejectToken(b -> b.expiration(null)); }
    @Test void rejectsWrongAuthorizedParty() { rejectToken(b -> b.claim("azp", "other")); }

    private void rejectToken(Consumer<io.jsonwebtoken.JwtBuilder> mutation) {
        expectToken(token(mutation));
        assertThatThrownBy(() -> client.authenticate("code", "verifier", "nonce")).isInstanceOf(ApiException.class);
        server.verify();
    }

    @Test void rejectsInvalidSignature() throws Exception {
        var signed = token(b -> {});
        keys = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        expectToken(token(b -> {}));
        assertThatThrownBy(() -> client.authenticate("code", "verifier", "nonce")).isInstanceOf(ApiException.class);
    }

    @Test void providerFailureDoesNotExposeResponseBody() {
        server.expect(requestTo(CONFIG.authority() + "/oauth2/v2.0/token"))
                .andRespond(withBadRequest().body("sensitive provider data"));
        assertThatThrownBy(() -> client.authenticate("code", "verifier", "nonce"))
                .isInstanceOf(ApiException.class).hasMessageNotContaining("sensitive");
    }

    @Test void configurationIsDisabledByDefaultAndNeverPrintsSecrets() {
        var disabled = new MicrosoftSsoProperties(false, null, null, "sensitive", null);
        assertThatThrownBy(disabled::requireEnabled).isInstanceOf(ApiException.class);
        assertThat(disabled.toString()).doesNotContain("sensitive");
        assertThatCode(CONFIG::requireEnabled).doesNotThrowAnyException();
    }
}
