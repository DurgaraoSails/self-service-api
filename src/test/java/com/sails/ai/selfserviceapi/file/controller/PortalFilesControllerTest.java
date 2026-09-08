package com.sails.ai.selfserviceapi.file.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sails.ai.selfserviceapi.common.exception.GlobalExceptionHandler;
import com.sails.ai.selfserviceapi.file.entity.UserFile;
import com.sails.ai.selfserviceapi.file.exception.FileNotFoundException;
import com.sails.ai.selfserviceapi.file.service.FileService;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Response shapes and status codes at the HTTP layer, mirroring PocFilesControllerTest. Security
 * is off here (covered by SecurityConfigTest, which proves this surface stays on the portal's
 * filter chain rather than the POC-files one) — this is the ownership handoff to FileService and
 * response mapping only.
 */
@WebMvcTest(controllers = PortalFilesController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class PortalFilesControllerTest {

    private static final UUID POC_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID MISSING_POC_ID = UUID.fromString("00000000-0000-0000-0000-000000000099");
    private static final UUID FILE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID MISSING_FILE_ID = UUID.fromString("00000000-0000-0000-0000-000000000099");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FileService fileService;

    @BeforeEach
    void authenticate() {
        Jwt jwt = new Jwt("token", Instant.now(), Instant.now().plusSeconds(300),
                Map.of("alg", "RS256"), Map.of("sub", "user-1"));
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listPassesTheCallersIdAndThePathsPocIdToFileService() throws Exception {
        UserFile file = new UserFile();
        file.setId(FILE_ID);
        file.setOriginalFilename("a.pdf");
        file.setContentType("application/pdf");
        file.setSizeBytes(10L);
        file.setUploadedAt(Instant.parse("2026-09-03T12:00:00Z"));
        when(fileService.list("user-1", POC_ID)).thenReturn(List.of(file));

        mockMvc.perform(get("/pocs/" + POC_ID + "/files"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(FILE_ID.toString()))
                .andExpect(jsonPath("$[0].originalFilename").value("a.pdf"));
    }

    /** A pocId the caller has nothing stored under is not an error — it is an empty list. */
    @Test
    void listReturnsEmptyForAPocWithNoFiles() throws Exception {
        when(fileService.list("user-1", MISSING_POC_ID)).thenReturn(List.of());

        mockMvc.perform(get("/pocs/" + MISSING_POC_ID + "/files"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void downloadSetsAttachmentDispositionAndTheStoredContentType() throws Exception {
        UserFile file = new UserFile();
        file.setId(FILE_ID);
        file.setOriginalFilename("report.pdf");
        file.setContentType("application/pdf");
        file.setSizeBytes(5L);
        when(fileService.download(eq("user-1"), eq(POC_ID), eq(FILE_ID)))
                .thenReturn(new FileService.FileDownload(file, new ByteArrayInputStream("hello".getBytes())));

        mockMvc.perform(get("/pocs/" + POC_ID + "/files/" + FILE_ID + "/content"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(content().bytes("hello".getBytes()));
    }

    /**
     * The point of reusing FileService's own scoped query rather than a separate check: a file
     * that belongs to this user under a *different* POC than the path names is not found, exactly
     * like a file belonging to someone else — both come back as the same 404.
     */
    @Test
    void downloadOfAFileUnderTheWrongPocReturns404() throws Exception {
        when(fileService.download(eq("user-1"), eq(POC_ID), eq(FILE_ID))).thenThrow(new FileNotFoundException(FILE_ID));

        mockMvc.perform(get("/pocs/" + POC_ID + "/files/" + FILE_ID + "/content"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletePassesTheCallersIdAndThePathsPocIdToFileService() throws Exception {
        mockMvc.perform(delete("/pocs/" + POC_ID + "/files/" + FILE_ID))
                .andExpect(status().isNoContent());

        org.mockito.Mockito.verify(fileService).delete("user-1", POC_ID, FILE_ID);
    }

    @Test
    void deleteOfAnUnknownFileReturns404() throws Exception {
        org.mockito.Mockito.doThrow(new FileNotFoundException(MISSING_FILE_ID))
                .when(fileService).delete(eq("user-1"), eq(POC_ID), eq(MISSING_FILE_ID));

        mockMvc.perform(delete("/pocs/" + POC_ID + "/files/" + MISSING_FILE_ID))
                .andExpect(status().isNotFound());
    }
}
