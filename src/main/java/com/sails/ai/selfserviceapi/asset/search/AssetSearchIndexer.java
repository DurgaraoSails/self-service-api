package com.sails.ai.selfserviceapi.asset.search;

import com.sails.ai.selfserviceapi.asset.entity.Asset;
import com.sails.ai.selfserviceapi.asset.entity.AssetRevision;
import com.sails.ai.selfserviceapi.asset.entity.Tag;
import com.sails.ai.selfserviceapi.asset.repository.AssetSearchRepository;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Builds and stores the search document for an asset's approved revision. Weighting matches the
 * Search Contract: title+tags = A, summary = B, problem/impact/solution = C, owner name = D.
 */
@Component
public class AssetSearchIndexer {

    private final AssetSearchRepository assetSearchRepository;

    public AssetSearchIndexer(AssetSearchRepository assetSearchRepository) {
        this.assetSearchRepository = assetSearchRepository;
    }

    public void index(Asset asset, AssetRevision approved, String ownerDisplayName) {
        String tagText = approved.getTags().stream().map(Tag::getName).collect(Collectors.joining(" "));
        String titleAndTags = (approved.getTitle() + " " + tagText).trim();
        String problemImpactSolution = String.join(" ",
                blank(approved.getProblemStatement()), blank(approved.getBusinessImpact()), blank(approved.getSolutionOverview())).trim();
        String searchText = String.join(" ", titleAndTags, approved.getSummary(), problemImpactSolution, blank(ownerDisplayName));

        assetSearchRepository.upsertSearchDocument(
                asset.getId(), approved.getId(), searchText,
                titleAndTags, approved.getSummary(), problemImpactSolution, blank(ownerDisplayName));
    }

    public void remove(UUID assetId) {
        assetSearchRepository.deleteById(assetId);
    }

    private static String blank(String value) {
        return value == null ? "" : value;
    }
}
