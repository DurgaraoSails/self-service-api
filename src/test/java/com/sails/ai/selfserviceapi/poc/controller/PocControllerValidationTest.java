package com.sails.ai.selfserviceapi.poc.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sails.ai.selfserviceapi.common.exception.GlobalExceptionHandler;
import com.sails.ai.selfserviceapi.poc.entity.Poc;
import com.sails.ai.selfserviceapi.poc.service.PocDeploymentService;
import com.sails.ai.selfserviceapi.poc.service.PocService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * That slug and githubUrl are actually rejected when missing, rather than merely documented as
 * required. The distinction is not theoretical here: this repo has already shipped a schema that
 * declared something OpenAPI-side while the runtime did the opposite, so "the spec says required"
 * is not evidence that a request without it fails.
 *
 * <p>Security filters are off — authorization is covered by SecurityConfigTest, and leaving them on
 * would mean every case below returned 401 before validation ever ran.
 */
@WebMvcTest(controllers = PocController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class PocControllerValidationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PocService pocService;

    @MockitoBean
    private PocDeploymentService pocDeploymentService;

    @Test
    void rejectsCreateWithoutASlug() throws Exception {
        mockMvc.perform(post("/pocs").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": "Contract Agent", "description": "Review contracts.",
                         "githubUrl": "https://github.com/example-org/contract-agent"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("slug")));
    }

    @Test
    void rejectsCreateWithoutAGithubUrl() throws Exception {
        mockMvc.perform(post("/pocs").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": "Contract Agent", "description": "Review contracts.",
                         "slug": "contract-agent"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("githubUrl")));
    }

    /** PUT replaces rather than merges, so an omitted githubUrl used to null a working POC's repo. */
    @Test
    void rejectsUpdateWithoutAGithubUrl() throws Exception {
        mockMvc.perform(put("/pocs/1").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": "Contract Agent", "description": "Review contracts.",
                         "slug": "contract-agent"}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void stillRejectsASlugThatIsNotUrlSafe() throws Exception {
        mockMvc.perform(post("/pocs").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": "Contract Agent", "description": "Review contracts.",
                         "slug": "Contract Agent",
                         "githubUrl": "https://github.com/example-org/contract-agent"}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void acceptsACreateCarryingBoth() throws Exception {
        Poc created = new Poc();
        created.setId(1L);
        Mockito.when(pocService.create(ArgumentMatchers.any())).thenReturn(created);
        Mockito.when(pocDeploymentService.latestDeploymentStatuses(ArgumentMatchers.any()))
                .thenReturn(java.util.Map.of());

        mockMvc.perform(post("/pocs").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": "Contract Agent", "description": "Review contracts.",
                         "slug": "contract-agent",
                         "githubUrl": "https://github.com/example-org/contract-agent"}"""))
                .andExpect(status().isCreated());
    }
}
