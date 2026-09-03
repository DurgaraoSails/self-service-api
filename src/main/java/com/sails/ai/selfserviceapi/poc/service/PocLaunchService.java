package com.sails.ai.selfserviceapi.poc.service;

import com.sails.ai.selfserviceapi.poc.entity.Poc;
import com.sails.ai.selfserviceapi.poc.exception.PocNotFoundException;
import com.sails.ai.selfserviceapi.poc.exception.PocNotLaunchableException;
import com.sails.ai.selfserviceapi.poc.repository.PocRepository;
import com.sails.ai.selfserviceapi.security.JwtService;
import com.sails.ai.selfserviceapi.user.entity.User;
import com.sails.ai.selfserviceapi.user.exception.UserNotFoundException;
import com.sails.ai.selfserviceapi.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single place where "may this user open this POC?" is answered.
 *
 * <p>Concentrating it here is the point rather than a convenience: every later check a POC makes —
 * reading the user's files, reading their saved state — trusts the claims in the token this
 * produces, so if entitlement is decided in more than one place, one of them will eventually be
 * wrong. The trial half of the question is not asked here at all; the endpoint sits behind the
 * existing trial gate in {@code SecurityConfig}, so an expired trial never reaches this code.
 */
@Service
public class PocLaunchService {

    private final PocRepository pocRepository;
    private final UserRepository userRepository;
    private final JwtService jwtService;

    public PocLaunchService(PocRepository pocRepository, UserRepository userRepository, JwtService jwtService) {
        this.pocRepository = pocRepository;
        this.userRepository = userRepository;
        this.jwtService = jwtService;
    }

    @Transactional(readOnly = true)
    public PocLaunch launch(String slug, String userId) {
        Poc poc = pocRepository.findBySlugAndDeletedAtIsNull(slug)
                .orElseThrow(() -> new PocNotFoundException(slug));

        // A hidden POC is a 404, not a 403. Distinguishing them would tell an unentitled caller
        // that a POC by this name exists and is merely withheld, which is exactly what hiding one
        // is meant to avoid. Admins are not exempted: hiding is how a POC is withdrawn, and a
        // withdrawn POC should not be launchable while it is withdrawn.
        if (!Poc.VISIBILITY_ACTIVE.equals(poc.getVisibilityStatus())) {
            throw new PocNotFoundException(slug);
        }

        if (poc.getAppUrl() == null || poc.getAppUrl().isBlank()) {
            throw new PocNotLaunchableException(slug);
        }

        User user = userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));

        return new PocLaunch(
                jwtService.issuePocToken(user, poc),
                jwtService.pocTokenTtlSeconds(),
                poc.getAppUrl(),
                poc.getId(),
                poc.getSlug());
    }

    public record PocLaunch(String token, long expiresInSeconds, String launchUrl, Long pocId, String slug) {
    }
}
