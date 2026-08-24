package com.sails.ai.selfserviceapi.user.service;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import com.sails.ai.selfserviceapi.user.config.TrialProperties;
import com.sails.ai.selfserviceapi.user.entity.User;
import com.sails.ai.selfserviceapi.user.entity.UserStatus;
import com.sails.ai.selfserviceapi.user.exception.UserAlreadyExistsException;
import com.sails.ai.selfserviceapi.user.exception.UserNotFoundException;
import com.sails.ai.selfserviceapi.user.repository.UserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
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
     * Registers a new user (status defaults to PENDING_VERIFICATION). Throws if the email is
     * already registered — registration never silently reactivates or logs in an existing user.
     */
    @Transactional
    public User registerUser(String firstName, String lastName, String companyName, String jobTitle, String country, String email) {
        emailDomainValidator.validate(email);

        if (userRepository.findByEmail(email).isPresent()) {
            throw new UserAlreadyExistsException();
        }

        Instant trialStart = Instant.now();

        User user = new User();
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

    @Transactional
    public User updateDisplayName(String id, String displayName) {
        User user = getById(id);
        user.setDisplayName(displayName);
        return userRepository.save(user);
    }
}
