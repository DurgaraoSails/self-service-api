package com.sails.ai.selfserviceapi.file.controller;

import com.sails.ai.selfserviceapi.file.entity.UserFile;
import com.sails.ai.selfserviceapi.file.service.FileResponseMapper;
import com.sails.ai.selfserviceapi.file.service.FileService;
import com.sails.ai.selfserviceapi.generated.api.PocFilesApi;
import com.sails.ai.selfserviceapi.generated.model.FileResponse;
import com.sails.ai.selfserviceapi.security.CurrentPoc;
import com.sails.ai.selfserviceapi.security.CurrentUser;
import java.util.List;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Sits behind the POC-files filter chain (see SecurityConfig) — every method here trusts that a
 * request only arrived because it carried a valid POC-scoped token, and reads the (user, POC)
 * scope from that token's claims via {@link CurrentUser}/{@link CurrentPoc} rather than from any
 * request parameter. There is no parameter naming a user or a POC on this controller at all.
 */
@RestController
public class PocFilesController implements PocFilesApi {

    private final FileService fileService;

    public PocFilesController(FileService fileService) {
        this.fileService = fileService;
    }

    @Override
    public ResponseEntity<FileResponse> uploadPocFile(MultipartFile file) {
        UserFile stored = fileService.upload(CurrentUser.id(), CurrentPoc.id(), file);
        return ResponseEntity.status(HttpStatus.CREATED).body(FileResponseMapper.toResponse(stored));
    }

    @Override
    public ResponseEntity<List<FileResponse>> listPocFiles() {
        List<FileResponse> files = fileService.list(CurrentUser.id(), CurrentPoc.id()).stream()
                .map(FileResponseMapper::toResponse)
                .toList();
        return ResponseEntity.ok(files);
    }

    @Override
    public ResponseEntity<Resource> getPocFileContent(Long fileId) {
        FileService.FileDownload download = fileService.download(CurrentUser.id(), CurrentPoc.id(), fileId);
        UserFile metadata = download.file();

        // attachment + nosniff (the latter applied by Spring Security's default header writer,
        // present on both filter chains) so a user-uploaded HTML or SVG file cannot execute in a
        // browser origin regardless of what its declared content type claims to be.
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
    public ResponseEntity<Void> deletePocFile(Long fileId) {
        fileService.delete(CurrentUser.id(), CurrentPoc.id(), fileId);
        return ResponseEntity.noContent().build();
    }
}
