package com.sails.ai.selfserviceapi.poc.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class DeploymentReconciliationPropertiesTest {

    @Test
    void rejectsAZeroOrNegativeStaleAfterWhenEnabled() {
        assertThatThrownBy(() -> new DeploymentReconciliationProperties(true, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deployment.reconciliation.stale-after");

        assertThatThrownBy(() -> new DeploymentReconciliationProperties(true, Duration.ofMinutes(-5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deployment.reconciliation.stale-after");

        assertThatThrownBy(() -> new DeploymentReconciliationProperties(true, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deployment.reconciliation.stale-after");
    }

    /** Disabled means the reconciler never reads staleAfter at all, so an unset value must not fail startup. */
    @Test
    void allowsNoStaleAfterWhenDisabled() {
        assertThatCode(() -> new DeploymentReconciliationProperties(false, null)).doesNotThrowAnyException();
    }

    @Test
    void acceptsAPositiveStaleAfterWhenEnabled() {
        assertThatCode(() -> new DeploymentReconciliationProperties(true, Duration.ofHours(1))).doesNotThrowAnyException();
    }
}
