package com.sails.ai.selfserviceapi.file.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sails.ai.selfserviceapi.common.exception.GlobalExceptionHandler;
import com.sails.ai.selfserviceapi.file.entity.UserFile;
import com.sails.ai.selfserviceapi.file.exception.FileNotFoundException;
import com.sails.ai.selfserviceapi.file.exception.FileQuotaExceededException;
import com.sails.ai.selfserviceapi.file.exception.UploadTooLargeException;
import com.sails.ai.selfserviceapi.file.service.FileService;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Response shapes and status codes at the HTTP layer — that FileService's exceptions actually
 * reach the client as the status codes the spec promises, not just that the exception classes
 * carry the right HttpStatus in isolation. Security is off (covered separately by
 * SecurityConfigTest); this is response mapping only.
 */
@WebMvcTest(controllers = PocFilesController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class PocFilesControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FileService fileService;

    /**
     * Security filters are off in this slice (see class Javadoc), so nothing normally populates
     * the SecurityContext the controller reads CurrentUser/CurrentPoc from. Authentication itself
     * is SecurityConfigTest's job; this stands in for it so the controller has something to read.
     */
    @BeforeEach
    void authenticate() {
        Jwt jwt = new Jwt("token", Instant.now(), Instant.now().plusSeconds(300),
                Map.of("alg", "RS256"), Map.of("sub", "user-1", "pocId", 4L));
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void uploadReturns201WithTheStoredFilesMetadata() throws Exception {
        UserFile stored = new UserFile();
        stored.setId(42L);
        stored.setOriginalFilename("report.pdf");
        stored.setContentType("application/pdf");
        stored.setSizeBytes(1024L);
        stored.setUploadedAt(Instant.parse("2026-09-03T12:00:00Z"));
        when(fileService.upload(any(), any(), any())).thenReturn(stored);

        MockMultipartFile file = new MockMultipartFile("file", "report.pdf", "application/pdf", new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/poc-files").file(file))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.originalFilename").value("report.pdf"))
                .andExpect(jsonPath("$.sizeBytes").value(1024));
    }

    @Test
    void uploadTooLargeReturns413() throws Exception {
        when(fileService.upload(any(), any(), any())).thenThrow(new UploadTooLargeException(20_000_000, 10_000_000));

        MockMultipartFile file = new MockMultipartFile("file", "big.pdf", "application/pdf", new byte[]{1});

        mockMvc.perform(multipart("/poc-files").file(file))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"));
    }

    @Test
    void quotaExceededReturns409() throws Exception {
        when(fileService.upload(any(), any(), any())).thenThrow(FileQuotaExceededException.perPocFileCount(20));

        MockMultipartFile file = new MockMultipartFile("file", "one-too-many.pdf", "application/pdf", new byte[]{1});

        mockMvc.perform(multipart("/poc-files").file(file))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FILE_QUOTA_EXCEEDED"));
    }

    @Test
    void listReturnsTheMappedFiles() throws Exception {
        UserFile file = new UserFile();
        file.setId(1L);
        file.setOriginalFilename("a.pdf");
        file.setContentType("application/pdf");
        file.setSizeBytes(10L);
        file.setUploadedAt(Instant.parse("2026-09-03T12:00:00Z"));
        when(fileService.list(any(), any())).thenReturn(List.of(file));

        mockMvc.perform(get("/poc-files"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].originalFilename").value("a.pdf"));
    }

    @Test
    void downloadSetsAttachmentDispositionAndTheStoredContentType() throws Exception {
        UserFile file = new UserFile();
        file.setId(1L);
        file.setOriginalFilename("report.pdf");
        file.setContentType("application/pdf");
        file.setSizeBytes(5L);
        when(fileService.download(any(), any(), eq(1L)))
                .thenReturn(new FileService.FileDownload(file, new ByteArrayInputStream("hello".getBytes())));

        mockMvc.perform(get("/poc-files/1/content"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("report.pdf")))
                .andExpect(content().bytes("hello".getBytes()));
    }

    @Test
    void downloadOfAnUnknownFileReturns404() throws Exception {
        when(fileService.download(any(), any(), eq(99L))).thenThrow(new FileNotFoundException(99L));

        mockMvc.perform(get("/poc-files/99/content"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteReturns204() throws Exception {
        mockMvc.perform(delete("/poc-files/1"))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteOfAnUnknownFileReturns404() throws Exception {
        org.mockito.Mockito.doThrow(new FileNotFoundException(99L)).when(fileService).delete(any(), any(), eq(99L));

        mockMvc.perform(delete("/poc-files/99"))
                .andExpect(status().isNotFound());
    }
}
