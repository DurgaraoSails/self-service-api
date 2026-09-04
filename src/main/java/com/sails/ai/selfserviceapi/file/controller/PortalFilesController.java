package com.sails.ai.selfserviceapi.file.controller;

import com.sails.ai.selfserviceapi.file.entity.UserFile;
import com.sails.ai.selfserviceapi.file.service.FileResponseMapper;
import com.sails.ai.selfserviceapi.file.service.FileService;
import com.sails.ai.selfserviceapi.generated.api.PortalFilesApi;
import com.sails.ai.selfserviceapi.generated.model.FileResponse;
import com.sails.ai.selfserviceapi.security.CurrentUser;
import java.util.List;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * The portal-facing half of file management (see docs/specs/file-management.md's API Surface):
 * the user's own access token, trial-gated by the default filter chain like any other portal
 * route, with the POC named explicitly by the path rather than a token claim. Deliberately reuses
 * {@link FileService} rather than duplicating it — every method there already scopes strictly by
 * (userId, pocId), so an id belonging to another user or a different POC is simply not found,
 * which is exactly the ownership check this surface needs.
 */
@RestController
public class PortalFilesController implements PortalFilesApi {

    private final FileService fileService;

    public PortalFilesController(FileService fileService) {
        this.fileService = fileService;
    }

    @Override
    public ResponseEntity<List<FileResponse>> listPocFilesForUser(Long id) {
        List<FileResponse> files = fileService.list(CurrentUser.id(), id).stream()
                .map(FileResponseMapper::toResponse)
                .toList();
        return ResponseEntity.ok(files);
    }

    @Override
    public ResponseEntity<Resource> getPocFileContentForUser(Long id, Long fileId) {
        FileService.FileDownload download = fileService.download(CurrentUser.id(), id, fileId);
        UserFile metadata = download.file();

        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(metadata.getOriginalFilename())
                .build();

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(metadata.getContentType()))
                .contentLength(metadata.getSizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(new InputStreamResource(download.content()));
    }

    @Override
    public ResponseEntity<Void> deletePocFileForUser(Long id, Long fileId) {
        fileService.delete(CurrentUser.id(), id, fileId);
        return ResponseEntity.noContent().build();
    }
}
