package com.sails.ai.selfserviceapi.asset.service;

import com.sails.ai.selfserviceapi.asset.repository.AssetEventRepository;
import com.sails.ai.selfserviceapi.asset.repository.AssetRepository;
import com.sails.ai.selfserviceapi.asset.repository.AssetReviewRepository;
import com.sails.ai.selfserviceapi.generated.model.AssetMetricsResponse;
import com.sails.ai.selfserviceapi.user.entity.AccountType;
import com.sails.ai.selfserviceapi.user.entity.UserStatus;
import com.sails.ai.selfserviceapi.user.repository.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computes the Metrics Contract's four numbers live, on every call — no scheduled aggregation job
 * or metrics table exists. See docs/specs/asset-hub.md's Metrics Contract for the exact formulas.
 */
@Service
public class AssetMetricsService {

    private static final Duration CONTRIBUTOR_ADOPTION_WINDOW = Duration.ofDays(90);

    private final AssetEventRepository assetEventRepository;
    private final AssetReviewRepository assetReviewRepository;
    private final AssetRepository assetRepository;
    private final UserRepository userRepository;

    public AssetMetricsService(AssetEventRepository assetEventRepository, AssetReviewRepository assetReviewRepository,
                                AssetRepository assetRepository, UserRepository userRepository) {
        this.assetEventRepository = assetEventRepository;
        this.assetReviewRepository = assetReviewRepository;
        this.assetRepository = assetRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public AssetMetricsResponse getMetrics() {
        Object[] searchStats = assetEventRepository.findSearchSuccessStats().get(0);
        long totalSearches = asLong(searchStats[0]);
        long successfulSearches = asLong(searchStats[1]);
        double successfulSearchRate = totalSearches == 0 ? 0.0 : (double) successfulSearches / totalSearches;

        long contributingEmployees = assetRepository.countDistinctSubmittersSince(
                Instant.now().minus(CONTRIBUTOR_ADOPTION_WINDOW));
        long activeInternalEmployees = userRepository.countByAccountTypeAndStatus(
                AccountType.INTERNAL, UserStatus.ACTIVE);
        double contributorAdoptionRate = activeInternalEmployees == 0 ? 0.0
                : (double) contributingEmployees / activeInternalEmployees;

        List<Object[]> turnaroundRows = assetReviewRepository.findReviewTurnaroundSecondsStats();
        Object[] turnaroundStats = turnaroundRows.isEmpty() ? new Object[]{null, null} : turnaroundRows.get(0);

        return new AssetMetricsResponse(totalSearches, successfulSearches, successfulSearchRate,
                contributingEmployees, activeInternalEmployees, contributorAdoptionRate)
                .timeToUsefulResultMedianSeconds(asNullableDouble(searchStats[2]))
                .timeToUsefulResultP90Seconds(asNullableDouble(searchStats[3]))
                .reviewTurnaroundMedianSeconds(asNullableDouble(turnaroundStats[0]))
                .reviewTurnaroundP90Seconds(asNullableDouble(turnaroundStats[1]));
    }

    private static long asLong(Object value) {
        return ((Number) value).longValue();
    }

    private static Double asNullableDouble(Object value) {
        return value == null ? null : ((Number) value).doubleValue();
    }
}
