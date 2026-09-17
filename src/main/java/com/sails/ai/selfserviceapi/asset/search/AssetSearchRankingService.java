package com.sails.ai.selfserviceapi.asset.search;

import com.sails.ai.selfserviceapi.asset.repository.AssetSearchRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Reciprocal-rank-fusion merge from the Search Contract: {@code 1 / (60 + lexicalRank) +
 * 1 / (60 + semanticRank)}, a missing rank contributing zero, ties broken by approved revision
 * {@code updated_at DESC} then {@code asset_id ASC}. Reached from
 * {@code AssetLifecycleService.listAssets} when a non-empty semantic candidate list exists — that
 * is, when {@code asset-hub.semantic-search-enabled=true}, an embedding provider is configured and
 * responding, and at least one approved asset has a stored embedding. Otherwise the caller stays on
 * the deterministic lexical path and this class is not involved.
 */
@Component
public class AssetSearchRankingService {

    private static final int RRF_K = 60;

    private final AssetSearchRepository assetSearchRepository;

    public AssetSearchRankingService(AssetSearchRepository assetSearchRepository) {
        this.assetSearchRepository = assetSearchRepository;
    }

    /**
     * Merges the two independently-ranked candidate lists into one fused ordering. Callers apply
     * their own page/size window to the result, same as every other paginated list in
     * {@code AssetLifecycleService}.
     */
    public List<UUID> merge(List<UUID> lexicalIds, List<UUID> semanticIds) {
        Map<UUID, Double> scores = new LinkedHashMap<>();
        addRrfScores(scores, lexicalIds);
        addRrfScores(scores, semanticIds);

        Map<UUID, Instant> approvedUpdatedAt = fetchApprovedRevisionUpdatedAt(scores.keySet());
        return scores.entrySet().stream()
                .sorted(Comparator.<Map.Entry<UUID, Double>>comparingDouble(Map.Entry::getValue).reversed()
                        .thenComparing((Map.Entry<UUID, Double> e) -> approvedUpdatedAt.getOrDefault(e.getKey(), Instant.EPOCH),
                                Comparator.reverseOrder())
                        .thenComparing(e -> e.getKey().toString()))
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * A native query's {@code timestamptz} column comes back as {@code java.sql.Timestamp} or
     * {@code java.time.Instant} depending on the JDBC driver version, not on anything this
     * codebase controls — the same query returned {@code Timestamp} in earlier testing and
     * {@code Instant} once this path first ran for real against a newer driver. Handling both
     * beats pinning to whichever one happened to be observed first.
     */
    private static Instant toInstant(Object value) {
        return switch (value) {
            case java.sql.Timestamp timestamp -> timestamp.toInstant();
            case Instant instant -> instant;
            default -> throw new IllegalStateException(
                    "Unexpected timestamp type from native query: " + value.getClass());
        };
    }

    private static void addRrfScores(Map<UUID, Double> scores, List<UUID> rankedIds) {
        for (int i = 0; i < rankedIds.size(); i++) {
            scores.merge(rankedIds.get(i), 1.0 / (RRF_K + i + 1), Double::sum);
        }
    }

    private Map<UUID, Instant> fetchApprovedRevisionUpdatedAt(Set<UUID> assetIds) {
        if (assetIds.isEmpty()) {
            return Map.of();
        }
        String assetIdsCsv = assetIds.stream().map(UUID::toString).collect(Collectors.joining(","));
        Map<UUID, Instant> result = new HashMap<>();
        for (Object[] row : assetSearchRepository.findApprovedRevisionUpdatedAt(assetIdsCsv)) {
            UUID assetId = (UUID) row[0];
            result.put(assetId, toInstant(row[1]));
        }
        return result;
    }
}
