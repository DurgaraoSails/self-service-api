package com.sails.ai.selfserviceapi.security;

import com.sails.ai.selfserviceapi.poc.entity.Poc;
import com.sails.ai.selfserviceapi.user.entity.User;
import io.jsonwebtoken.Jwts;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    private final RSAPrivateKey jwtPrivateKey;
    private final JwtProperties jwtProperties;
    private final JwtKeySet jwtKeySet;

    public JwtService(RSAPrivateKey jwtPrivateKey, JwtProperties jwtProperties, JwtKeySet jwtKeySet) {
        this.jwtPrivateKey = jwtPrivateKey;
        this.jwtProperties = jwtProperties;
        this.jwtKeySet = jwtKeySet;
    }

    public String issueAccessToken(User user) {
        Instant now = Instant.now();
        Instant expiry = now.plus(jwtProperties.accessTokenTtl());

        var builder = Jwts.builder()
                // Costs one line now; adding it once POCs are verifying tokens would mean every
                // cached key set and every issued token turning over together.
                .header().keyId(jwtKeySet.keyId()).and()
                .issuer(jwtProperties.issuer())
                .subject(user.getId())
                .claim("email", user.getEmail())
                .claim("roles", user.getRoles())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .id(UUID.randomUUID().toString());

        if (user.getTenantId() != null) {
            builder.claim("tenantId", user.getTenantId());
        }

        if (user.getTrialEndDate() != null) {
            builder.claim("trialEndDate", user.getTrialEndDate().getEpochSecond());
        }

        return builder.signWith(jwtPrivateKey, Jwts.SIG.RS256).compact();
    }

    /**
     * Mints the token a POC runs on. Carries only what a POC legitimately needs — who the user is,
     * what to call them, and which theme to render in.
     *
     * <p>Three omissions are deliberate. There are no {@code roles}, so a POC cannot inherit an
     * admin's authority by being launched by one. There is no email, which a POC has no use for.
     * And there is no refresh token anywhere in this flow: when this one nears expiry the POC asks
     * the portal, which calls the launch endpoint again, so signing out of the portal ends POC
     * access at the next refresh without any revocation machinery.
     *
     * <p>{@code trialEndDate} is carried so the same {@link TrialAuthorizationManager} that gates
     * the portal also gates whatever this token reaches, rather than the POC-facing surface
     * growing a second, separately-maintained expiry rule.
     */
    public String issuePocToken(User user, Poc poc) {
        Instant now = Instant.now();
        Instant expiry = now.plus(jwtProperties.pocTokenTtl());

        var builder = Jwts.builder()
                .header().keyId(jwtKeySet.keyId()).and()
                .issuer(jwtProperties.issuer())
                .subject(user.getId())
                .audience().add(PocAudience.forSlug(poc.getSlug())).and()
                // The numeric id as well as the slug in the audience: file storage is keyed on the
                // id precisely because a slug can be renamed, and carrying it here saves a lookup
                // on every request a POC makes.
                .claim("pocId", poc.getId())
                .claim("name", displayNameOf(user))
                .claim("theme", user.getTheme().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .id(UUID.randomUUID().toString());

        if (user.getTrialEndDate() != null) {
            builder.claim("trialEndDate", user.getTrialEndDate().getEpochSecond());
        }

        return builder.signWith(jwtPrivateKey, Jwts.SIG.RS256).compact();
    }

    public long accessTokenTtlSeconds() {
        return jwtProperties.accessTokenTtl().toSeconds();
    }

    public long pocTokenTtlSeconds() {
        return jwtProperties.pocTokenTtl().toSeconds();
    }

    /** What the POC shows the user. Falls back to their name, since displayName is optional. */
    private static String displayNameOf(User user) {
        if (user.getDisplayName() != null && !user.getDisplayName().isBlank()) {
            return user.getDisplayName();
        }
        return "%s %s".formatted(user.getFirstName(), user.getLastName()).trim();
    }
}
