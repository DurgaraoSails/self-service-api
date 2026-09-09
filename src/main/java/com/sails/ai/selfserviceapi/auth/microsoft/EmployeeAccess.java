package com.sails.ai.selfserviceapi.auth.microsoft;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import com.sails.ai.selfserviceapi.user.entity.AccountType;
import com.sails.ai.selfserviceapi.user.entity.User;
import java.util.Locale;
import org.springframework.http.HttpStatus;

/** Domain routing is not authentication; INTERNAL is assigned only after Entra verification. */
public final class EmployeeAccess {
    private EmployeeAccess() {}

    public static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean companyEmail(String email) {
        return normalize(email).matches("[^@\\s]+@sailssoftware\\.com");
    }

    public static void requireExternal(String email) {
        if (companyEmail(email)) throw ssoRequired();
    }

    public static void requireExternal(User user) {
        if (user.getAccountType() == AccountType.INTERNAL) throw ssoRequired();
        requireExternal(user.getEmail());
    }

    public static void requireTrialAccount(User user) {
        if (user.getAccountType() == AccountType.INTERNAL) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TRIAL_NOT_APPLICABLE", "Employees do not have a trial.");
        }
    }

    private static ApiException ssoRequired() {
        return new ApiException(HttpStatus.FORBIDDEN, "MICROSOFT_SSO_REQUIRED", "Use Microsoft sign-in with your company account.");
    }
}
