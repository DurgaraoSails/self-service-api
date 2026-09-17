package com.sails.ai.selfserviceapi.asset.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.sails.ai.selfserviceapi.asset.repository.AssetEventRepository;
import com.sails.ai.selfserviceapi.asset.repository.AssetRepository;
import com.sails.ai.selfserviceapi.asset.repository.AssetReviewRepository;
import com.sails.ai.selfserviceapi.generated.model.AssetMetricsResponse;
import com.sails.ai.selfserviceapi.user.entity.AccountType;
import com.sails.ai.selfserviceapi.user.entity.UserStatus;
import com.sails.ai.selfserviceapi.user.repository.UserRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

class AssetMetricsServiceTest {

    private AssetEventRepository assetEventRepository;
    private AssetReviewRepository assetReviewRepository;
    private AssetRepository assetRepository;
    private UserRepository userRepository;
    private AssetMetricsService service;

    @BeforeEach
    void setUp() {
        assetEventRepository = Mockito.mock(AssetEventRepository.class);
        assetReviewRepository = Mockito.mock(AssetReviewRepository.class);
        assetRepository = Mockito.mock(AssetRepository.class);
        userRepository = Mockito.mock(UserRepository.class);
        service = new AssetMetricsService(assetEventRepository, assetReviewRepository, assetRepository, userRepository);
    }

    @Test
    void computesRatesFromRepositoryStats() {
        when(assetEventRepository.findSearchSuccessStats())
                .thenReturn(List.<Object[]>of(new Object[]{10L, 4L, 30.0, 90.0}));
        when(assetReviewRepository.findReviewTurnaroundSecondsStats())
                .thenReturn(List.<Object[]>of(new Object[]{3600.0, 7200.0}));
        when(assetRepository.countDistinctSubmittersSince(ArgumentMatchers.any())).thenReturn(3L);
        when(userRepository.countByAccountTypeAndStatus(AccountType.INTERNAL, UserStatus.ACTIVE)).thenReturn(12L);

        AssetMetricsResponse response = service.getMetrics();

        assertThat(response.getTotalSearches()).isEqualTo(10L);
        assertThat(response.getSuccessfulSearches()).isEqualTo(4L);
        assertThat(response.getSuccessfulSearchRate()).isEqualTo(0.4);
        assertThat(response.getTimeToUsefulResultMedianSeconds()).isEqualTo(30.0);
        assertThat(response.getTimeToUsefulResultP90Seconds()).isEqualTo(90.0);
        assertThat(response.getContributingEmployees()).isEqualTo(3L);
        assertThat(response.getActiveInternalEmployees()).isEqualTo(12L);
        assertThat(response.getContributorAdoptionRate()).isEqualTo(0.25);
        assertThat(response.getReviewTurnaroundMedianSeconds()).isEqualTo(3600.0);
        assertThat(response.getReviewTurnaroundP90Seconds()).isEqualTo(7200.0);
    }

    @Test
    void treatsZeroSearchesAndZeroEmployeesAsZeroRatesRatherThanDividingByZero() {
        when(assetEventRepository.findSearchSuccessStats())
                .thenReturn(List.<Object[]>of(new Object[]{0L, 0L, null, null}));
        when(assetReviewRepository.findReviewTurnaroundSecondsStats()).thenReturn(List.of());
        when(assetRepository.countDistinctSubmittersSince(ArgumentMatchers.any())).thenReturn(0L);
        when(userRepository.countByAccountTypeAndStatus(AccountType.INTERNAL, UserStatus.ACTIVE)).thenReturn(0L);

        AssetMetricsResponse response = service.getMetrics();

        assertThat(response.getSuccessfulSearchRate()).isEqualTo(0.0);
        assertThat(response.getContributorAdoptionRate()).isEqualTo(0.0);
        assertThat(response.getTimeToUsefulResultMedianSeconds()).isNull();
        assertThat(response.getTimeToUsefulResultP90Seconds()).isNull();
        assertThat(response.getReviewTurnaroundMedianSeconds()).isNull();
        assertThat(response.getReviewTurnaroundP90Seconds()).isNull();
    }
}
