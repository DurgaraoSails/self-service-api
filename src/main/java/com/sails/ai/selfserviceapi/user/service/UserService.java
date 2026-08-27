package com.sails.ai.selfserviceapi.user.service;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import com.sails.ai.selfserviceapi.user.config.TrialProperties;
import com.sails.ai.selfserviceapi.user.entity.ThemeMode;
import com.sails.ai.selfserviceapi.user.entity.User;
import com.sails.ai.selfserviceapi.user.entity.UserStatus;
import com.sails.ai.selfserviceapi.user.exception.UserAlreadyExistsException;
import com.sails.ai.selfserviceapi.user.exception.UserNotFoundException;
import com.sails.ai.selfserviceapi.user.repository.UserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final TrialProperties trialProperties;
    private final EmailDomainValidator emailDomainValidator;

    public UserService(UserRepository userRepository, TrialProperties trialProperties, EmailDomainValidator emailDomainValidator) {
        this.userRepository = userRepository;
        this.trialProperties = trialProperties;
        this.emailDomainValidator = emailDomainValidator;
    }

    public User getById(String id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException(id));
    }

    /**
     * Looks up an existing user by email and requires it to be ACTIVE (i.e. registered and
     * email-verified). Never creates a user — account creation happens through registration,
     * not through a login/verification step. Used once a user is known to already be verified:
     * the interim JWT login, and after OTP verification has promoted a user to ACTIVE.
     */
    public User getActiveByEmail(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "No account found for this email."));
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new ApiException(HttpStatus.FORBIDDEN, "EMAIL_NOT_VERIFIED", "Verify your email before requesting a login code.");
        }
        return user;
    }

    /**
     * Looks up an existing user by email for OTP purposes, where the OTP itself may be *doing*
     * the verification (registration) rather than requiring it upfront (login). Allows
     * PENDING_VERIFICATION and ACTIVE; rejects INACTIVE/SUSPENDED accounts.
     */
    public User getEligibleForOtpByEmail(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "No account found for this email."));
        if (user.getStatus() == UserStatus.INACTIVE || user.getStatus() == UserStatus.SUSPENDED) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_INACTIVE", "This account cannot receive login codes.");
        }
        return user;
    }

    /**
     * Registers a new user (status defaults to PENDING_VERIFICATION), or re-registers over an
     * existing but never-verified one. An email only counts as "already registered" once it has
     * completed OTP verification (emailVerifiedAt is set) — an unverified PENDING_VERIFICATION
     * row from an abandoned signup is overwritten in place (same id, fresh trial window) rather
     * than blocking the new attempt with a 409.
     */
    @Transactional
    public User registerUser(String firstName, String lastName, String companyName, String jobTitle, String country, String email) {
        emailDomainValidator.validate(email);

        Optional<User> existingByEmail = userRepository.findByEmail(email);
        if (existingByEmail.isPresent() && existingByEmail.get().getEmailVerifiedAt() != null) {
            throw new UserAlreadyExistsException();
        }

        Instant trialStart = Instant.now();

        User user = existingByEmail.orElseGet(User::new);
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setCompanyName(companyName);
        user.setJobTitle(jobTitle);
        user.setCountry(country);
        user.setEmail(email);
        user.setRoles(new ArrayList<>(List.of("USER")));
        user.setTrialStartDate(trialStart);
        user.setTrialEndDate(trialStart.plus(trialProperties.lengthDays(), ChronoUnit.DAYS));
        return userRepository.save(user);
    }

    /**
     * Updates only the fields that are non-null — PUT /users/me is a partial update over the
     * mutable profile fields, not a full-replace: toggling the theme must not wipe out a
     * previously-set displayName (and vice versa) just because the caller didn't resend it.
     */
    @Transactional
    public User updateProfile(String id, String displayName, ThemeMode theme) {
        User user = getById(id);
        if (displayName != null) {
            user.setDisplayName(displayName);
        }
        if (theme != null) {
            user.setTheme(theme);
        }
        return userRepository.save(user);
    }

    /**
     * Consumes the one-time first-login signal (used to drive the guided tour) by persisting it
     * as false. No-op if already cleared, so repeated logins don't issue redundant writes.
     */
    @Transactional
    public void clearFirstLoginFlag(User user) {
        if (user.isFirstLogin()) {
            user.setFirstLogin(false);
            userRepository.save(user);
        }
    }
        
    /**
     * Admin Customers list. {@code needsAttention} takes priority over the registration-date bounds
     * when set — an admin clearing their pending-action badge wants everyone who needs it, not just
     * the ones who also happen to fall in whatever date range was previously selected. {@code search}
     * (first/last name, company, or email) combines with either filter.
     */
    public Page<User> listCustomers(boolean needsAttention, Instant registeredFrom, Instant registeredTo, String search, Pageable pageable) {
        Specification<User> spec = (root, query, cb) -> cb.conjunction();
        if (needsAttention) {
            spec = spec.and(UserSpecifications.needsAttention(Instant.now(), needsAttentionCutoff()));
        } else if (registeredFrom != null || registeredTo != null) {
            Instant from = registeredFrom != null ? registeredFrom : Instant.EPOCH;
            Instant to = registeredTo != null ? registeredTo : Instant.now();
            spec = spec.and(UserSpecifications.registeredBetween(from, to));
        }
        if (search != null && !search.isBlank()) {
            spec = spec.and(UserSpecifications.matchesSearch(search.trim()));
        }
        return userRepository.findAll(spec, pageable);
    }

    public long countNeedingAttention() {
        return userRepository.count(UserSpecifications.needsAttention(Instant.now(), needsAttentionCutoff()));
    }

    @Transactional
    public User revokeTrial(String id) {
        User user = getById(id);
        user.setTrialEndDate(Instant.now());
        clearPendingExtensionRequest(user);
        return userRepository.save(user);
    }

    @Transactional
    public User extendTrial(String id, Instant newTrialEndDate) {
        Instant now = Instant.now();
        if (newTrialEndDate.isBefore(now)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TRIAL_END_DATE_IN_PAST", "Trial end date must be in the future.");
        }
        if (newTrialEndDate.isAfter(now.plus(trialProperties.lengthDays(), ChronoUnit.DAYS))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TRIAL_EXTENSION_TOO_FAR",
                    "Trial end date cannot be more than " + trialProperties.lengthDays() + " days from now.");
        }
        User user = getById(id);
        user.setTrialEndDate(newTrialEndDate);
        clearPendingExtensionRequest(user);
        return userRepository.save(user);
    }

    /**
     * Records that the user has asked for more time, for an admin to see later (Customers list
     * note icon + the "needs attention" badge/filter). Sending the actual notification email is a
     * separate concern, handled by {@code SupportService}.
     */
    @Transactional
    public User requestTrialExtension(String id, String note) {
        User user = getById(id);
        user.setPendingExtensionNote(note);
        user.setPendingExtensionRequestedAt(Instant.now());
        return userRepository.save(user);
    }

    private void clearPendingExtensionRequest(User user) {
        user.setPendingExtensionNote(null);
        user.setPendingExtensionRequestedAt(null);
    }

    private Instant needsAttentionCutoff() {
        return Instant.now().plus(1, ChronoUnit.DAYS);
    }
}
