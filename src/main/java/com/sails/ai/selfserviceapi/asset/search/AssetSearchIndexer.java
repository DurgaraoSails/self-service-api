package com.sails.ai.selfserviceapi.asset.search;

import com.sails.ai.selfserviceapi.asset.ai.EmbeddingProvider;
import com.sails.ai.selfserviceapi.asset.ai.EmbeddingProviderException;
import com.sails.ai.selfserviceapi.asset.entity.Asset;
import com.sails.ai.selfserviceapi.asset.entity.AssetRevision;
import com.sails.ai.selfserviceapi.asset.entity.Tag;
import com.sails.ai.selfserviceapi.asset.repository.AssetSearchRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Builds and stores the search document for an asset's approved revision. Weighting matches the
 * Search Contract: title+tags = A, summary = B, problem/impact/solution = C, owner name = D.
 *
 * <p>When an {@link EmbeddingProvider} bean exists ({@code asset-hub.semantic-search-enabled=true}
 * — currently never true in any real deployment, see docs/specs/asset-hub.md's "Semantic search
 * embeddings: Voyage AI"), this records provider/model/dimensions/checksum metadata alongside the
 * lexical document. It does not persist the embedding vector itself: the
 * {@code asset_search_documents.embedding} column does not exist until the still-pending Phase 3
 * pgvector migration, so there is nowhere to put it yet. An embedding failure is logged and
 * swallowed, exactly like a lexical-only index today — indexing an approved revision must never
 * fail because the optional intelligence layer did.
 */
@Component
public class AssetSearchIndexer {

    private static final Logger log = LoggerFactory.getLogger(AssetSearchIndexer.class);

    private final AssetSearchRepository assetSearchRepository;
    private final ObjectProvider<EmbeddingProvider> embeddingProvider;

    public AssetSearchIndexer(AssetSearchRepository assetSearchRepository,
                               ObjectProvider<EmbeddingProvider> embeddingProvider) {
        this.assetSearchRepository = assetSearchRepository;
        this.embeddingProvider = embeddingProvider;
    }

    public void index(Asset asset, AssetRevision approved, String ownerDisplayName) {
        String tagText = approved.getTags().stream().map(Tag::getName).collect(Collectors.joining(" "));
        String titleAndTags = (approved.getTitle() + " " + tagText).trim();
        String problemImpactSolution = String.join(" ",
                blank(approved.getProblemStatement()), blank(approved.getBusinessImpact()), blank(approved.getSolutionOverview())).trim();
        String searchText = String.join(" ", titleAndTags, approved.getSummary(), problemImpactSolution, blank(ownerDisplayName));

        String embeddingProviderName = null;
        String embeddingModel = null;
        Integer embeddingDimensions = null;
        String embeddingChecksum = null;
        EmbeddingProvider provider = embeddingProvider.getIfAvailable();
        if (provider != null) {
            try {
                provider.embed(searchText);
                embeddingProviderName = provider.providerName();
                embeddingModel = provider.modelName();
                embeddingDimensions = provider.dimensions();
                embeddingChecksum = checksum(searchText);
            } catch (EmbeddingProviderException e) {
                log.warn("Embedding generation failed for asset {} ({}); indexing lexical-only.",
                        asset.getId(), e.errorCode());
            }
        }

        assetSearchRepository.upsertSearchDocument(
                asset.getId(), approved.getId(), searchText,
                titleAndTags, approved.getSummary(), problemImpactSolution, blank(ownerDisplayName),
                embeddingProviderName, embeddingModel, embeddingDimensions, embeddingChecksum);
    }

    public void remove(UUID assetId) {
        assetSearchRepository.deleteById(assetId);
    }

    private static String blank(String value) {
        return value == null ? "" : value;
    }

    private static String checksum(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is a required JDK algorithm", e);
        }
    }
}
