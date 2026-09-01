package com.sails.ai.selfserviceapi.deployment.service;

import com.sails.ai.selfserviceapi.deployment.entity.PocDeployment;
import com.sails.ai.selfserviceapi.deployment.repository.PocDeploymentRepository;
import com.sails.ai.selfserviceapi.poc.entity.Poc;
import com.sails.ai.selfserviceapi.poc.repository.PocRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Read side of the pipeline: the fleet-wide "what is building / live / broken" view.
 *
 * Exists separately from PocService.listForViewer because that one deliberately hides everything
 * not live — this is the opposite view, and the only way an admin can see a POC that failed to
 * deploy or is still building.
 */
@Service
public class DeploymentQueryService {

    private final PocRepository pocRepository;
    private final PocDeploymentRepository pocDeploymentRepository;

    public DeploymentQueryService(PocRepository pocRepository, PocDeploymentRepository pocDeploymentRepository) {
        this.pocRepository = pocRepository;
        this.pocDeploymentRepository = pocDeploymentRepository;
    }

    /** @param deploymentStatus filter on the POC's pipeline state, or null for all POCs. */
    public List<PocWithLatestDeployment> listLatestDeployments(String deploymentStatus) {
        List<Poc> pocs = deploymentStatus == null
                ? pocRepository.findByDeletedAtIsNull()
                : pocRepository.findByDeploymentStatusAndDeletedAtIsNull(deploymentStatus);

        if (pocs.isEmpty()) {
            return List.of();
        }

        Map<Long, PocDeployment> latestByPocId = latestDeploymentByPocId(pocs.stream().map(Poc::getId).toList());

        // Most recent activity first, so a build that just failed is at the top of the admin's
        // screen. POCs that have never deployed have nothing to sort by and sit at the end.
        return pocs.stream()
                .map(poc -> new PocWithLatestDeployment(poc, latestByPocId.get(poc.getId())))
                .sorted(Comparator
                        .comparing(
                                (PocWithLatestDeployment row) -> row.latestDeployment() == null
                                        ? Instant.MIN
                                        : row.latestDeployment().getStartedAt(),
                                Comparator.reverseOrder())
                        .thenComparing(row -> row.poc().getName(), Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private Map<Long, PocDeployment> latestDeploymentByPocId(List<Long> pocIds) {
        Map<Long, PocDeployment> latest = new HashMap<>();
        // Rows arrive newest-first, so the first one seen for a poc_id is its latest.
        for (PocDeployment deployment : pocDeploymentRepository.findByPocIdInOrderByCreatedAtDesc(pocIds)) {
            latest.putIfAbsent(deployment.getPocId(), deployment);
        }
        return latest;
    }

    public record PocWithLatestDeployment(Poc poc, PocDeployment latestDeployment) {
    }
}
