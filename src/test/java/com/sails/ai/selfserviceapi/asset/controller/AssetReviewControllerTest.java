package com.sails.ai.selfserviceapi.asset.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sails.ai.selfserviceapi.asset.exception.SelfReviewForbiddenException;
import com.sails.ai.selfserviceapi.asset.service.AssetMetricsService;
import com.sails.ai.selfserviceapi.asset.service.AssetReviewService;
import com.sails.ai.selfserviceapi.common.exception.GlobalExceptionHandler;
import com.sails.ai.selfserviceapi.generated.model.AssetMetricsResponse;
import com.sails.ai.selfserviceapi.generated.model.AssetReviewDetailResponse;
import com.sails.ai.selfserviceapi.generated.model.AssetReviewQueuePageResponse;
import com.sails.ai.selfserviceapi.generated.model.AssetReviewerDashboardResponse;
import com.sails.ai.selfserviceapi.generated.model.CreateAssetReviewRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(controllers = AssetReviewController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class AssetReviewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AssetReviewService assetReviewService;

    @MockitoBean
    private AssetMetricsService assetMetricsService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listAssetReviewsReturns200ForAnAssetReviewer() throws Exception {
        authenticate("reviewer-1", List.of(new SimpleGrantedAuthority("ROLE_ASSET_REVIEWER")));
        when(assetReviewService.listAssetReviews(any(), any(), anyInt(), anyInt()))
                .thenReturn(new AssetReviewQueuePageResponse(List.of(), 0, 20, 0L, 0));

        mockMvc.perform(get("/asset-reviews"))
                .andExpect(status().isOk());
    }

    @Test
    void listAssetReviewsReturns403WithoutTheAssetReviewerRole() throws Exception {
        authenticate("employee-1", List.of());

        mockMvc.perform(get("/asset-reviews"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ASSET_REVIEWER_REQUIRED"));
    }

    @Test
    void getAssetReviewerDashboardReturns200() throws Exception {
        authenticate("reviewer-1", List.of(new SimpleGrantedAuthority("ROLE_ASSET_REVIEWER")));
        when(assetReviewService.getAssetReviewerDashboard())
                .thenReturn(new AssetReviewerDashboardResponse(5L, 3L, 1L, List.of()));

        mockMvc.perform(get("/asset-reviews/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAssets").value(5));
    }

    @Test
    void createAssetReviewDecisionReturns403WhenTheReviewerIsTheSubmitterOrAuthor() throws Exception {
        authenticate("reviewer-1", List.of(new SimpleGrantedAuthority("ROLE_ASSET_REVIEWER")));
        UUID revisionId = UUID.randomUUID();
        when(assetReviewService.createAssetReviewDecision(eq(revisionId), eq("reviewer-1"), any()))
                .thenThrow(new SelfReviewForbiddenException());
        CreateAssetReviewRequest request = new CreateAssetReviewRequest(
                CreateAssetReviewRequest.DecisionEnum.APPROVE, 1L);

        mockMvc.perform(post("/asset-reviews/{revisionId}/decisions", revisionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SELF_REVIEW_FORBIDDEN"));
    }

    @Test
    void createAssetReviewDecisionReturns200OnSuccess() throws Exception {
        authenticate("reviewer-1", List.of(new SimpleGrantedAuthority("ROLE_ASSET_REVIEWER")));
        UUID revisionId = UUID.randomUUID();
        when(assetReviewService.createAssetReviewDecision(eq(revisionId), eq("reviewer-1"), any()))
                .thenReturn(new AssetReviewDetailResponse());
        CreateAssetReviewRequest request = new CreateAssetReviewRequest(
                CreateAssetReviewRequest.DecisionEnum.APPROVE, 1L);

        mockMvc.perform(post("/asset-reviews/{revisionId}/decisions", revisionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    @Test
    void getAssetHubMetricsReturns200ForAnAssetReviewer() throws Exception {
        authenticate("reviewer-1", List.of(new SimpleGrantedAuthority("ROLE_ASSET_REVIEWER")));
        when(assetMetricsService.getMetrics()).thenReturn(
                new AssetMetricsResponse(0L, 0L, 0.0, 0L, 0L, 0.0));

        mockMvc.perform(get("/asset-hub/metrics"))
                .andExpect(status().isOk());
    }

    @Test
    void getAssetHubMetricsReturns403WithoutTheAssetReviewerRole() throws Exception {
        authenticate("employee-1", List.of());

        mockMvc.perform(get("/asset-hub/metrics"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ASSET_REVIEWER_REQUIRED"));
    }

    private static void authenticate(String userId, List<? extends GrantedAuthority> authorities) {
        Jwt jwt = new Jwt("token", Instant.now(), Instant.now().plusSeconds(300),
                Map.of("alg", "RS256"), Map.of("sub", userId, "accountType", "INTERNAL"));
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, authorities));
    }
}
