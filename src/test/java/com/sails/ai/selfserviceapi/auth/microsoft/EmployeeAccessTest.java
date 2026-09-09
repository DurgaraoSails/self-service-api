package com.sails.ai.selfserviceapi.auth.microsoft;

import static org.assertj.core.api.Assertions.*;
import com.sails.ai.selfserviceapi.common.exception.ApiException;
import com.sails.ai.selfserviceapi.user.entity.AccountType;
import com.sails.ai.selfserviceapi.user.entity.User;
import org.junit.jupiter.api.Test;

class EmployeeAccessTest {
    @Test void exactDomainOnly() {
        assertThat(EmployeeAccess.companyEmail(" Jane@SAILSSOFTWARE.COM ")).isTrue();
        for (String email : new String[]{"x@evil.sailssoftware.com", "x@sailssoftware.com.evil", "@sailssoftware.com", "x@@sailssoftware.com", "x@example.com"}) {
            assertThat(EmployeeAccess.companyEmail(email)).isFalse();
        }
    }
    @Test void companyAndLinkedAccountsCannotUseLegacyLogin() {
        assertThatThrownBy(() -> EmployeeAccess.requireExternal("x@sailssoftware.com")).isInstanceOf(ApiException.class);
        User user = new User(); user.setAccountType(AccountType.INTERNAL); user.setEmail("changed@other.com");
        assertThatThrownBy(() -> EmployeeAccess.requireExternal(user)).isInstanceOf(ApiException.class);
        assertThatCode(() -> EmployeeAccess.requireExternal("x@example.com")).doesNotThrowAnyException();
    }
    @Test void employeesCannotBeAssignedTrials() {
        User user = new User(); user.setAccountType(AccountType.INTERNAL);
        assertThatThrownBy(() -> EmployeeAccess.requireTrialAccount(user)).isInstanceOf(ApiException.class);
    }
    @Test void pkceMatchesRfc7636Example() {
        assertThat(MicrosoftAuthorizationStore.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
                .isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
    }
}
