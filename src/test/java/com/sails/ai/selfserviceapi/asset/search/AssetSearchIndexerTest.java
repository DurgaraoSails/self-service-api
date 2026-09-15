package com.sails.ai.selfserviceapi.asset.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sails.ai.selfserviceapi.asset.ai.EmbeddingProvider;
import com.sails.ai.selfserviceapi.asset.ai.EmbeddingProviderException;
import com.sails.ai.selfserviceapi.asset.entity.Asset;
import com.sails.ai.selfserviceapi.asset.entity.AssetRevision;
import com.sails.ai.selfserviceapi.asset.entity.Tag;
import com.sails.ai.selfserviceapi.asset.repository.AssetSearchRepository;
import java.util.HashSet;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

class AssetSearchIndexerTest {

    private AssetSearchRepository assetSearchRepository;
    @SuppressWarnings("unchecked")
    private final ObjectProvider<EmbeddingProvider> embeddingProvider = Mockito.mock(ObjectProvider.class);
    private AssetSearchIndexer indexer;

    @BeforeEach
    void setUp() {
        assetSearchRepository = Mockito.mock(AssetSearchRepository.class);
        indexer = new AssetSearchIndexer(assetSearchRepository, embeddingProvider);
    }

    @Test
    void indexesOnlyApprovedFieldsWithNullEmbeddingMetadataWhenNoProviderIsConfigured() {
        when(embeddingProvider.getIfAvailable()).thenReturn(null);
        Asset asset = asset();
        AssetRevision approved = approvedRevision("Radiology Triage", "A summary", "Healthcare");

        indexer.index(asset, approved, "Jane Doe");

        ArgumentCaptor<String> searchText = ArgumentCaptor.forClass(String.class);
        verify(assetSearchRepository).upsertSearchDocument(
                eq(asset.getId()), eq(approved.getId()), searchText.capture(),
                eq("Radiology Triage Healthcare"), eq("A summary"), any(), eq("Jane Doe"),
                isNull(), isNull(), isNull(), isNull());
        assertThat(searchText.getValue()).contains("Radiology Triage", "A summary", "Jane Doe");
    }

    @Test
    void recordsEmbeddingMetadataWhenAProviderSucceeds() {
        EmbeddingProvider provider = Mockito.mock(EmbeddingProvider.class);
        when(embeddingProvider.getIfAvailable()).thenReturn(provider);
        when(provider.embed(any())).thenReturn(new float[]{0.1f, 0.2f});
        when(provider.providerName()).thenReturn("voyage");
        when(provider.modelName()).thenReturn("voyage-4");
        when(provider.dimensions()).thenReturn(1024);
        Asset asset = asset();
        AssetRevision approved = approvedRevision("Title", "Summary", null);

        indexer.index(asset, approved, "Owner");

        verify(assetSearchRepository).upsertSearchDocument(
                any(), any(), any(), any(), any(), any(), any(),
                eq("voyage"), eq("voyage-4"), eq(1024), any());
    }

    @Test
    void fallsBackToLexicalOnlyWhenEmbeddingGenerationFails() {
        EmbeddingProvider provider = Mockito.mock(EmbeddingProvider.class);
        when(embeddingProvider.getIfAvailable()).thenReturn(provider);
        when(provider.embed(any())).thenThrow(new EmbeddingProviderException("PROVIDER_TIMEOUT", "timed out"));
        Asset asset = asset();
        AssetRevision approved = approvedRevision("Title", "Summary", null);

        indexer.index(asset, approved, "Owner");

        verify(assetSearchRepository).upsertSearchDocument(
                any(), any(), any(), any(), any(), any(), any(),
                isNull(), isNull(), isNull(), isNull());
    }

    @Test
    void removeDeletesTheSearchDocumentByAssetId() {
        UUID assetId = UUID.randomUUID();
        indexer.remove(assetId);
        verify(assetSearchRepository).deleteById(assetId);
        verify(assetSearchRepository, never()).upsertSearchDocument(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    private static Asset asset() {
        Asset asset = new Asset();
        asset.setId(UUID.randomUUID());
        return asset;
    }

    private static AssetRevision approvedRevision(String title, String summary, String tagName) {
        AssetRevision revision = new AssetRevision();
        revision.setId(UUID.randomUUID());
        revision.setTitle(title);
        revision.setSummary(summary);
        HashSet<Tag> tags = new HashSet<>();
        if (tagName != null) {
            Tag tag = new Tag();
            tag.setName(tagName);
            tags.add(tag);
        }
        revision.setTags(tags);
        return revision;
    }
}
