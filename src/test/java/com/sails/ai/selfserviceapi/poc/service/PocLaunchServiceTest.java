package com.sails.ai.selfserviceapi.poc.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import com.sails.ai.selfserviceapi.poc.entity.Poc;
import com.sails.ai.selfserviceapi.poc.repository.PocRepository;
import com.sails.ai.selfserviceapi.security.JwtService;
import com.sails.ai.selfserviceapi.user.entity.User;
import com.sails.ai.selfserviceapi.user.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;

class PocLaunchServiceTest {

    private PocRepository pocRepository;
    private UserRepository userRepository;
    private JwtService jwtService;
    private PocLaunchService pocLaunchService;

    @BeforeEach
    void setUp() {
        pocRepository = Mockito.mock(PocRepository.class);
        userRepository = Mockito.mock(UserRepository.class);
        jwtService = Mockito.mock(JwtService.class);
        pocLaunchService = new PocLaunchService(pocRepository, userRepository, jwtService);

        when(jwtService.issuePocToken(any(), any())).thenReturn("a-poc-token");
        when(jwtService.pocTokenTtlSeconds()).thenReturn(900L);
    }

    @Test
    void mintsATokenForAnActiveDeployedPoc() {
        givenPoc(activePoc());
        givenUser();

        PocLaunchService.PocLaunch launch = pocLaunchService.launch("contract-agent", "user-1");

        assertThat(launch.token()).isEqualTo("a-poc-token");
        assertThat(launch.expiresInSeconds()).isEqualTo(900L);
        assertThat(launch.launchUrl()).isEqualTo("https://contract-agent.example.run.app");
        assertThat(launch.pocId()).isEqualTo(4L);
        assertThat(launch.slug()).isEqualTo("contract-agent");
    }

    @Test
    void refusesAnUnknownSlug() {
        when(pocRepository.findBySlugAndDeletedAtIsNull("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> pocLaunchService.launch("nope", "user-1"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * 404, not 403. Telling an unentitled caller that a POC by this name exists and is merely
     * withheld defeats the purpose of withdrawing it.
     */
    @Test
    void reportsAHiddenPocAsNotFoundRatherThanForbidden() {
        Poc hidden = activePoc();
        hidden.setVisibilityStatus("HIDDEN");
        givenPoc(hidden);

        assertThatThrownBy(() -> pocLaunchService.launch("contract-agent", "user-1"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void refusesAPocThatHasNeverBeenDeployed() {
        Poc undeployed = activePoc();
        undeployed.setAppUrl(null);
        givenPoc(undeployed);

        assertThatThrownBy(() -> pocLaunchService.launch("contract-agent", "user-1"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void mintsNoTokenWhenEntitlementFails() {
        Poc hidden = activePoc();
        hidden.setVisibilityStatus("HIDDEN");
        givenPoc(hidden);

        assertThatThrownBy(() -> pocLaunchService.launch("contract-agent", "user-1"))
                .isInstanceOf(ApiException.class);

        verify(jwtService, never()).issuePocToken(any(), any());
    }

    @Test
    void refusesWhenTheCallerNoLongerExists() {
        givenPoc(activePoc());
        when(userRepository.findById("user-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> pocLaunchService.launch("contract-agent", "user-1"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private void givenPoc(Poc poc) {
        when(pocRepository.findBySlugAndDeletedAtIsNull("contract-agent")).thenReturn(Optional.of(poc));
    }

    private void givenUser() {
        User user = new User();
        user.setId("user-1");
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
    }

    private static Poc activePoc() {
        Poc poc = new Poc();
        poc.setId(4L);
        poc.setSlug("contract-agent");
        poc.setVisibilityStatus(Poc.VISIBILITY_ACTIVE);
        poc.setAppUrl("https://contract-agent.example.run.app");
        return poc;
    }
}
