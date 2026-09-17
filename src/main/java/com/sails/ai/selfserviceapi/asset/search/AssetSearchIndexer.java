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
 * <p>When an {@link EmbeddingProvider} bean exists ({@code asset-hub.semantic-search-enabled=true}),
 * this also embeds the same text and stores the vector in
 * {@code asset_search_documents.embedding} (added in {@code V26}) along with
 * provider/model/dimensions/checksum metadata. An embedding failure is logged and swallowed,
 * leaving a lexical-only row: indexing an approved revision must never fail because the optional
 * intelligence layer did, and {@code findSemanticCandidateIds} skips null-embedding rows.
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

        String embedding = null;
        String embeddingProviderName = null;
        String embeddingModel = null;
        Integer embeddingDimensions = null;
        String embeddingChecksum = null;
        EmbeddingProvider provider = embeddingProvider.getIfAvailable();
        if (provider != null) {
            try {
                embedding = VectorLiteral.of(provider.embed(searchText));
                embeddingProviderName = provider.providerName();
                embeddingModel = provider.modelName();
                embeddingDimensions = provider.dimensions();
                embeddingChecksum = checksum(searchText);
            } catch (EmbeddingProviderException e) {
                log.warn("Embedding generation failed for asset {} ({}); indexing lexical-only.",
                        asset.getId(), e.errorCode());
            }
        }

        // A null embedding overwrites any previous one on re-approval, which is intended: the
        // stored vector must describe the revision currently indexed, and a stale vector from the
        // previous approved revision would keep answering semantic queries with superseded text.
        assetSearchRepository.upsertSearchDocument(
                asset.getId(), approved.getId(), searchText,
                titleAndTags, approved.getSummary(), problemImpactSolution, blank(ownerDisplayName),
                embedding, embeddingProviderName, embeddingModel, embeddingDimensions, embeddingChecksum);
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
