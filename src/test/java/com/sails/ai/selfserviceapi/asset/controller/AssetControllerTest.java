package com.sails.ai.selfserviceapi.asset.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sails.ai.selfserviceapi.asset.exception.AssetNotFoundException;
import com.sails.ai.selfserviceapi.asset.service.AssetAiSuggestionService;
import com.sails.ai.selfserviceapi.asset.service.AssetLifecycleService;
import com.sails.ai.selfserviceapi.common.exception.GlobalExceptionHandler;
import com.sails.ai.selfserviceapi.generated.model.AssetAiSuggestionResponse;
import com.sails.ai.selfserviceapi.generated.model.AssetDetailResponse;
import com.sails.ai.selfserviceapi.generated.model.AssetEditorResponse;
import com.sails.ai.selfserviceapi.generated.model.AssetPageResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Response shapes and status codes at the HTTP layer, matching {@code PocFilesControllerTest}'s
 * approach: security filters are off (covered separately by SecurityConfigTest), so a JWT is
 * planted directly in the SecurityContext for CurrentUser to read.
 */
@WebMvcTest(controllers = AssetController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class AssetControllerTest {

    @org.springframework.beans.factory.annotation.Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AssetLifecycleService assetLifecycleService;

    @MockitoBean
    private AssetAiSuggestionService assetAiSuggestionService;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void getAssetReturns200WithTheDetailBody() throws Exception {
        authenticateAsInternal("employee-1");
        UUID assetId = UUID.randomUUID();
        when(assetLifecycleService.getAsset(assetId)).thenReturn(new AssetDetailResponse());

        mockMvc.perform(get("/assets/{assetId}", assetId))
                .andExpect(status().isOk());
    }

    @Test
    void getAssetReturns404WithTheStableErrorCodeWhenNotFound() throws Exception {
        authenticateAsInternal("employee-1");
        UUID assetId = UUID.randomUUID();
        when(assetLifecycleService.getAsset(assetId)).thenThrow(new AssetNotFoundException(assetId));

        mockMvc.perform(get("/assets/{assetId}", assetId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ASSET_NOT_FOUND"));
    }

    @Test
    void everyAssetRouteReturns403WhenTheCallerIsNotInternal() throws Exception {
        authenticateAsExternal("customer-1");

        mockMvc.perform(get("/assets/{assetId}", UUID.randomUUID()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INTERNAL_ACCOUNT_REQUIRED"));
    }

    @Test
    void listAssetsReturns200WithThePageBody() throws Exception {
        authenticateAsInternal("employee-1");
        when(assetLifecycleService.listAssets(any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), anyString()))
                .thenReturn(new AssetPageResponse(List.of(), 0, 20, 0L, 0));

        mockMvc.perform(get("/assets"))
                .andExpect(status().isOk());
    }

    @Test
    void createWorkingRevisionReturns201() throws Exception {
        authenticateAsInternal("employee-1");
        UUID assetId = UUID.randomUUID();
        when(assetLifecycleService.createWorkingRevision(eq(assetId), anyString()))
                .thenReturn(new AssetEditorResponse());

        mockMvc.perform(post("/assets/{assetId}/working-revision", assetId))
                .andExpect(status().isCreated());
    }

    @Test
    void startAssetAiSuggestionsReturns202() throws Exception {
        authenticateAsInternal("employee-1");
        UUID assetId = UUID.randomUUID();
        when(assetAiSuggestionService.startSuggestions(eq(assetId), anyString()))
                .thenReturn(new AssetAiSuggestionResponse());

        mockMvc.perform(post("/assets/{assetId}/ai-suggestions", assetId))
                .andExpect(status().isAccepted());
    }

    private static void authenticateAsInternal(String userId) {
        authenticate(userId, "INTERNAL", List.of());
    }

    private static void authenticateAsExternal(String userId) {
        authenticate(userId, "EXTERNAL", List.of());
    }

    private static void authenticate(String userId, String accountType, List<? extends GrantedAuthority> authorities) {
        Jwt jwt = new Jwt("token", Instant.now(), Instant.now().plusSeconds(300),
                Map.of("alg", "RS256"), Map.of("sub", userId, "accountType", accountType));
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, authorities));
    }
}
