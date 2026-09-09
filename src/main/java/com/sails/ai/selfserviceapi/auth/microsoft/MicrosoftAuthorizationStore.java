package com.sails.ai.selfserviceapi.auth.microsoft;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.Base64;
import java.util.HexFormat;
import com.sails.ai.selfserviceapi.common.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MicrosoftAuthorizationStore {
    private final JdbcTemplate jdbc;
    private final SecureRandom random = new SecureRandom();

    public MicrosoftAuthorizationStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Pending(String state, String nonce) {}

    @Transactional
    public Pending create(String challenge) {
        jdbc.update("DELETE FROM microsoft_authorizations WHERE expires_at <= ?", Timestamp.from(Instant.now()));
        Pending pending = new Pending(randomValue(), randomValue());
        jdbc.update("INSERT INTO microsoft_authorizations(state_hash, nonce, code_challenge, expires_at) VALUES (?, ?, ?, ?)",
                hash(pending.state()), pending.nonce(), challenge, Timestamp.from(Instant.now().plusSeconds(600)));
        return pending;
    }

    /** Commits consumption before any external call, even when the subsequent login fails. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String consume(String state, String verifier) {
        var nonces = jdbc.query("DELETE FROM microsoft_authorizations WHERE state_hash = ? AND code_challenge = ? AND expires_at > ? RETURNING nonce",
                (rs, row) -> rs.getString(1), hash(state), challenge(verifier), Timestamp.from(Instant.now()));
        if (nonces.size() != 1) throw new ApiException(HttpStatus.BAD_REQUEST, "MICROSOFT_STATE_INVALID",
                "This Microsoft sign-in has expired or was already used. Please start again.");
        return nonces.getFirst();
    }

    private String randomValue() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    static String challenge(String verifier) { return Base64.getUrlEncoder().withoutPadding().encodeToString(digest(verifier)); }
    private static String hash(String value) { return HexFormat.of().formatHex(digest(value)); }
    private static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
