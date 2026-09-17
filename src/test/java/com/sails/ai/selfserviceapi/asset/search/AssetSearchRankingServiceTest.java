package com.sails.ai.selfserviceapi.asset.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sails.ai.selfserviceapi.asset.repository.AssetSearchRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The RRF merge path was unreachable in every real deployment until semantic search actually
 * worked end-to-end (findSemanticCandidateIds always returned empty with no embeddings stored),
 * so a driver-dependent bug in the tie-break lookup went uncaught by every other test in this
 * suite. Confirmed live: the JDBC driver paired with PostgreSQL 17 + pgvector returns
 * java.time.Instant for a timestamptz column, not java.sql.Timestamp like earlier testing implied
 * — a hard cast to Timestamp threw ClassCastException on the first real semantic search.
 */
class AssetSearchRankingServiceTest {

    private final AssetSearchRepository repository = mock(AssetSearchRepository.class);
    private final AssetSearchRankingService service = new AssetSearchRankingService(repository);

    @Test
    void mergesWhenTheDriverReturnsJavaTimeInstant() {
        UUID assetId = UUID.randomUUID();
        when(repository.findApprovedRevisionUpdatedAt(any()))
                .thenReturn(List.<Object[]>of(new Object[]{assetId, Instant.now()}));

        assertThat(service.merge(List.of(assetId), List.of())).containsExactly(assetId);
    }

    @Test
    void mergesWhenTheDriverReturnsJavaSqlTimestamp() {
        UUID assetId = UUID.randomUUID();
        when(repository.findApprovedRevisionUpdatedAt(any()))
                .thenReturn(List.<Object[]>of(new Object[]{assetId, Timestamp.from(Instant.now())}));

        assertThat(service.merge(List.of(assetId), List.of())).containsExactly(assetId);
    }

    @Test
    void fusesLexicalAndSemanticCandidatesByReciprocalRank() {
        UUID lexicalOnly = UUID.randomUUID();
        UUID semanticOnly = UUID.randomUUID();
        UUID inBoth = UUID.randomUUID();
        when(repository.findApprovedRevisionUpdatedAt(any())).thenReturn(List.<Object[]>of(
                new Object[]{lexicalOnly, Instant.now()},
                new Object[]{semanticOnly, Instant.now()},
                new Object[]{inBoth, Instant.now()}));

        List<UUID> merged = service.merge(List.of(inBoth, lexicalOnly), List.of(inBoth, semanticOnly));

        // Present in both candidate lists sums two RRF terms and must rank first.
        assertThat(merged.get(0)).isEqualTo(inBoth);
        assertThat(merged).containsExactlyInAnyOrder(lexicalOnly, semanticOnly, inBoth);
    }
}
