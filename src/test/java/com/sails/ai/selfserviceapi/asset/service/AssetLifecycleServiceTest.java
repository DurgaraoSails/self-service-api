package com.sails.ai.selfserviceapi.asset.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sails.ai.selfserviceapi.asset.entity.AssetEvent;
import com.sails.ai.selfserviceapi.asset.repository.AssetEventRepository;
import com.sails.ai.selfserviceapi.asset.repository.AssetFeedbackRepository;
import com.sails.ai.selfserviceapi.asset.repository.AssetRepository;
import com.sails.ai.selfserviceapi.asset.repository.AssetReviewRepository;
import com.sails.ai.selfserviceapi.asset.repository.AssetRevisionRepository;
import com.sails.ai.selfserviceapi.asset.repository.AssetSearchRepository;
import com.sails.ai.selfserviceapi.asset.repository.TagRepository;
import com.sails.ai.selfserviceapi.asset.search.AssetSearchIndexer;
import com.sails.ai.selfserviceapi.common.exception.ApiException;
import com.sails.ai.selfserviceapi.generated.model.AssetPageResponse;
import com.sails.ai.selfserviceapi.generated.model.CreateAssetRequest;
import com.sails.ai.selfserviceapi.poc.repository.PocRepository;
import com.sails.ai.selfserviceapi.user.repository.UserRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class AssetLifecycleServiceTest {

    private AssetSearchRepository assetSearchRepository;
    private AssetEventRepository assetEventRepository;
    private AssetLifecycleService service;

    @BeforeEach
    void setUp() {
        AssetRepository assetRepository = Mockito.mock(AssetRepository.class);
        AssetRevisionRepository assetRevisionRepository = Mockito.mock(AssetRevisionRepository.class);
        assetSearchRepository = Mockito.mock(AssetSearchRepository.class);
        TagRepository tagRepository = Mockito.mock(TagRepository.class);
        UserRepository userRepository = Mockito.mock(UserRepository.class);
        PocRepository pocRepository = Mockito.mock(PocRepository.class);
        AssetFeedbackRepository assetFeedbackRepository = Mockito.mock(AssetFeedbackRepository.class);
        assetEventRepository = Mockito.mock(AssetEventRepository.class);
        AssetReviewRepository assetReviewRepository = Mockito.mock(AssetReviewRepository.class);
        AssetSearchIndexer assetSearchIndexer = Mockito.mock(AssetSearchIndexer.class);
        service = new AssetLifecycleService(assetRepository, assetRevisionRepository, assetSearchRepository,
                tagRepository, userRepository, pocRepository, assetFeedbackRepository, assetEventRepository,
                assetReviewRepository, assetSearchIndexer);
    }

    @Test
    void rejectsNonHttpSourceUrlsBeforeWritingAnAsset() {
        CreateAssetRequest request = new CreateAssetRequest(CreateAssetRequest.AssetTypeEnum.DOCUMENT,
                "owner", "Title", "Summary", "file:///C:/secret.txt");

        assertThatThrownBy(() -> service.createAsset(request, "author"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("absolute HTTP(S)");
    }

    @Test
    void recordsAPrivacyMinimizedSearchEventForKeywordSearches() {
        when(assetSearchRepository.findRankedAssetIds(eq("onboarding"), any(), any(), any(), eq(false),
                eq(20), eq(0))).thenReturn(List.of());
        when(assetSearchRepository.countRankedAssets(eq("onboarding"), any(), any(), any(), eq(false)))
                .thenReturn(0L);

        AssetPageResponse response = service.listAssets("  onboarding  ", null, null, null,
                false, 0, 20, "employee-1");

        ArgumentCaptor<AssetEvent> event = ArgumentCaptor.forClass(AssetEvent.class);
        verify(assetEventRepository).save(event.capture());
        assertThat(event.getValue().getEventType()).isEqualTo("SEARCH");
        assertThat(event.getValue().getUserId()).isEqualTo("employee-1");
        assertThat(event.getValue().getAssetId()).isNull();
        assertThat(event.getValue().getSearchSessionId()).isEqualTo(response.getSearchSessionId());
    }
}
