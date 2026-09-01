package com.sails.ai.selfserviceapi.deployment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sails.ai.selfserviceapi.deployment.entity.PocDeployment;
import com.sails.ai.selfserviceapi.deployment.repository.PocDeploymentRepository;
import com.sails.ai.selfserviceapi.deployment.service.DeploymentQueryService.PocWithLatestDeployment;
import com.sails.ai.selfserviceapi.poc.entity.Poc;
import com.sails.ai.selfserviceapi.poc.repository.PocRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class DeploymentQueryServiceTest {

    private PocRepository pocRepository;
    private PocDeploymentRepository pocDeploymentRepository;
    private DeploymentQueryService service;

    @BeforeEach
    void setUp() {
        pocRepository = Mockito.mock(PocRepository.class);
        pocDeploymentRepository = Mockito.mock(PocDeploymentRepository.class);
        service = new DeploymentQueryService(pocRepository, pocDeploymentRepository);
    }

    private static Poc poc(Long id, String name, String deploymentStatus) {
        Poc poc = new Poc();
        poc.setId(id);
        poc.setName(name);
        poc.setSlug(name.toLowerCase());
        poc.setDeploymentStatus(deploymentStatus);
        return poc;
    }

    private static PocDeployment deployment(Long id, Long pocId, String tag, Instant startedAt) {
        PocDeployment deployment = new PocDeployment();
        deployment.setId(id);
        deployment.setPocId(pocId);
        deployment.setReleaseTag(tag);
        deployment.setStartedAt(startedAt);
        return deployment;
    }

    @Test
    void keepsOnlyTheNewestDeploymentPerPoc() {
        Poc alpha = poc(1L, "Alpha", "active");
        when(pocRepository.findByDeletedAtIsNull()).thenReturn(List.of(alpha));
        // Repository contract is newest-first; three attempts for the same POC.
        when(pocDeploymentRepository.findByPocIdInOrderByCreatedAtDesc(anyCollection())).thenReturn(List.of(
                deployment(30L, 1L, "1.2.0", Instant.parse("2026-09-01T03:00:00Z")),
                deployment(20L, 1L, "1.1.0", Instant.parse("2026-09-01T02:00:00Z")),
                deployment(10L, 1L, "1.0.0", Instant.parse("2026-09-01T01:00:00Z"))));

        List<PocWithLatestDeployment> rows = service.listLatestDeployments(null);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).latestDeployment().getReleaseTag()).isEqualTo("1.2.0");
    }

    @Test
    void filtersByDeploymentStatusWhenOneIsGiven() {
        Poc failed = poc(2L, "Broken", "failed");
        when(pocRepository.findByDeploymentStatusAndDeletedAtIsNull("failed")).thenReturn(List.of(failed));
        when(pocDeploymentRepository.findByPocIdInOrderByCreatedAtDesc(anyCollection())).thenReturn(List.of(
                deployment(40L, 2L, "1.0.0", Instant.parse("2026-09-01T01:00:00Z"))));

        List<PocWithLatestDeployment> rows = service.listLatestDeployments("failed");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).poc().getName()).isEqualTo("Broken");
        Mockito.verify(pocRepository, Mockito.never()).findByDeletedAtIsNull();
    }

    @Test
    void includesPocsThatHaveNeverDeployedAndSortsThemLast() {
        Poc neverDeployed = poc(1L, "Seeded", "active");
        Poc recentlyBuilt = poc(2L, "Pipelined", "failed");
        when(pocRepository.findByDeletedAtIsNull()).thenReturn(List.of(neverDeployed, recentlyBuilt));
        when(pocDeploymentRepository.findByPocIdInOrderByCreatedAtDesc(anyCollection())).thenReturn(List.of(
                deployment(50L, 2L, "1.0.0", Instant.parse("2026-09-01T05:00:00Z"))));

        List<PocWithLatestDeployment> rows = service.listLatestDeployments(null);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).poc().getName()).isEqualTo("Pipelined");
        assertThat(rows.get(1).poc().getName()).isEqualTo("Seeded");
        assertThat(rows.get(1).latestDeployment()).isNull();
    }

    @Test
    void skipsTheDeploymentLookupEntirelyWhenNoPocsMatch() {
        when(pocRepository.findByDeploymentStatusAndDeletedAtIsNull("building")).thenReturn(List.of());

        assertThat(service.listLatestDeployments("building")).isEmpty();

        verifyNoInteractions(pocDeploymentRepository);
    }
}
