package com.sails.ai.selfserviceapi.file.service;

import com.sails.ai.selfserviceapi.file.entity.UserFile;
import com.sails.ai.selfserviceapi.generated.model.FileResponse;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** Static, matching PocResponseMapper's and UserResponseMapper's pattern in this codebase. */
public final class FileResponseMapper {

    private FileResponseMapper() {
    }

    public static FileResponse toResponse(UserFile userFile) {
        return new FileResponse(
                userFile.getId(),
                userFile.getOriginalFilename(),
                userFile.getContentType(),
                userFile.getSizeBytes(),
                OffsetDateTime.ofInstant(userFile.getUploadedAt(), ZoneOffset.UTC));
    }
}
