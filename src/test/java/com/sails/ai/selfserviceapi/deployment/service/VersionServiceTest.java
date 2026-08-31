package com.sails.ai.selfserviceapi.deployment.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sails.ai.selfserviceapi.deployment.service.VersionService.BumpType;
import java.util.List;
import org.junit.jupiter.api.Test;

class VersionServiceTest {

    private final VersionService versionService = new VersionService();

    @Test
    void returnsOneZeroZeroWhenNoTagsExist() {
        assertThat(versionService.computeNextVersion(List.of())).isEqualTo("1.0.0");
    }

    @Test
    void defaultsToAMinorBumpWhenNoBumpTypeGiven() {
        assertThat(versionService.computeNextVersion(List.of("1.2.0"))).isEqualTo("1.3.0");
    }

    @Test
    void picksTheHighestExistingVersionRegardlessOfListOrder() {
        List<String> shuffled = List.of("1.9.0", "2.0.0", "1.10.0");
        assertThat(versionService.computeNextVersion(shuffled, BumpType.PATCH)).isEqualTo("2.0.1");
    }

    @Test
    void appliesAMajorBump() {
        assertThat(versionService.computeNextVersion(List.of("2.4.7"), BumpType.MAJOR)).isEqualTo("3.0.0");
    }

    @Test
    void appliesAMinorBump() {
        assertThat(versionService.computeNextVersion(List.of("2.4.7"), BumpType.MINOR)).isEqualTo("2.5.0");
    }

    @Test
    void appliesAPatchBump() {
        assertThat(versionService.computeNextVersion(List.of("2.4.7"), BumpType.PATCH)).isEqualTo("2.4.8");
    }

    @Test
    void ignoresTagsThatAreNotBareSemver() {
        List<String> tags = List.of("v1.2.0", "latest", "test-abc123de", "1.5.0");
        assertThat(versionService.computeNextVersion(tags, BumpType.PATCH)).isEqualTo("1.5.1");
    }
}
