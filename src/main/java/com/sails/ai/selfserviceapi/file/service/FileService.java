package com.sails.ai.selfserviceapi.file.service;

import com.github.f4b6a3.ulid.UlidCreator;
import com.sails.ai.selfserviceapi.file.config.FileStorageProperties;
import com.sails.ai.selfserviceapi.file.entity.UserFile;
import com.sails.ai.selfserviceapi.file.exception.FileNotFoundException;
import com.sails.ai.selfserviceapi.file.exception.FileQuotaExceededException;
import com.sails.ai.selfserviceapi.file.exception.FileStorageException;
import com.sails.ai.selfserviceapi.file.exception.UploadTooLargeException;
import com.sails.ai.selfserviceapi.file.repository.UserFileRepository;
import com.sails.ai.selfserviceapi.file.storage.FileStorage;
import com.sails.ai.selfserviceapi.file.storage.ObjectPaths;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Everything a caller can do with a user's uploaded documents: store one, list them, read one
 * back, remove one. Every method takes {@code userId}/{@code pocId} as plain parameters rather
 * than reading them from the security context itself — the controller resolves those from the
 * token (via {@code CurrentUser}/{@code CurrentPoc} on the POC-facing surface, or the path and
 * {@code CurrentUser} on the portal-facing one), so this class stays agnostic to which surface
 * called it and is testable with no security context at all.
 */
@Service
public class FileService {

    private final UserFileRepository userFileRepository;
    private final FileStorage fileStorage;
    private final ContentTypeValidator contentTypeValidator;
    private final FileStorageProperties properties;

    public FileService(UserFileRepository userFileRepository,
                        FileStorage fileStorage,
                        ContentTypeValidator contentTypeValidator,
                        FileStorageProperties properties) {
        this.userFileRepository = userFileRepository;
        this.fileStorage = fileStorage;
        this.contentTypeValidator = contentTypeValidator;
        this.properties = properties;
    }

    /**
     * Validates quota and content type, writes the object, then persists its row — in that order,
     * so a row is never created for an object that failed to store, and quota is never charged for
     * an upload rejected before anything was written.
     */
    @Transactional
    public UserFile upload(String userId, Long pocId, MultipartFile file) {
        long sizeBytes = file.getSize();
        if (sizeBytes > properties.maxFileSizeBytes()) {
            throw new UploadTooLargeException(sizeBytes, properties.maxFileSizeBytes());
        }

        if (userFileRepository.countByUserIdAndPocIdAndDeletedAtIsNull(userId, pocId) >= properties.maxFilesPerPoc()) {
            throw FileQuotaExceededException.perPocFileCount(properties.maxFilesPerPoc());
        }

        long existingTotalBytes = userFileRepository.sumLiveSizeBytesByUserId(userId);
        if (existingTotalBytes + sizeBytes > properties.maxBytesPerUserBytes()) {
            throw FileQuotaExceededException.perUserTotalBytes(properties.maxBytesPerUserBytes());
        }

        String declaredContentType = file.getContentType();
        contentTypeValidator.validate(declaredContentType, sample(file));

        String fileId = UlidCreator.getUlid().toString();
        String objectName = ObjectPaths.object(userId, pocId, fileId);
        fileStorage.store(objectName, declaredContentType, contentOf(file));

        UserFile userFile = new UserFile();
        userFile.setUserId(userId);
        userFile.setPocId(pocId);
        userFile.setObjectName(objectName);
        // Multipart parts may omit a filename entirely; a document with no name to show the user
        // is still storable, so this falls back rather than rejecting the upload over display text.
        String originalFilename = file.getOriginalFilename();
        userFile.setOriginalFilename(originalFilename == null || originalFilename.isBlank() ? "unnamed" : originalFilename);
        userFile.setContentType(declaredContentType);
        userFile.setSizeBytes(sizeBytes);

        return userFileRepository.save(userFile);
    }

    @Transactional(readOnly = true)
    public List<UserFile> list(String userId, Long pocId) {
        return userFileRepository.findByUserIdAndPocIdAndDeletedAtIsNullOrderByUploadedAtDesc(userId, pocId);
    }

    /** The caller closes {@link FileDownload#content()}. */
    @Transactional(readOnly = true)
    public FileDownload download(String userId, Long pocId, Long fileId) {
        UserFile userFile = findOwned(userId, pocId, fileId);
        return new FileDownload(userFile, fileStorage.open(userFile.getObjectName()));
    }

    /**
     * Removes the object immediately, freeing the caller's quota right away, then marks the row
     * deleted rather than removing it — the row is what purge accounting and the total-bytes sum
     * exclude it from, and what distinguishes "deleted by the user" from "never existed" if this
     * fails partway through. Object before row, matching the ordering the purge job uses for the
     * same reason: an orphaned row pointing at a gone object is a harmless inconsistency the next
     * read surfaces as 404; an orphaned object with no row is data nothing can find to purge.
     */
    @Transactional
    public void delete(String userId, Long pocId, Long fileId) {
        UserFile userFile = findOwned(userId, pocId, fileId);
        fileStorage.delete(userFile.getObjectName());
        userFile.setDeletedAt(Instant.now());
        userFileRepository.save(userFile);
    }

    private UserFile findOwned(String userId, Long pocId, Long fileId) {
        return userFileRepository.findByIdAndUserIdAndPocIdAndDeletedAtIsNull(fileId, userId, pocId)
                .orElseThrow(() -> new FileNotFoundException(fileId));
    }

    /** Bounded read for magic-byte sniffing; storage gets its own fresh stream below. */
    private byte[] sample(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return in.readNBytes(ContentTypeValidator.SAMPLE_BYTES);
        } catch (IOException e) {
            throw new FileStorageException("Failed to read upload for content-type validation", e);
        }
    }

    private InputStream contentOf(MultipartFile file) {
        try {
            return file.getInputStream();
        } catch (IOException e) {
            throw new FileStorageException("Failed to read upload for storage", e);
        }
    }

    public record FileDownload(UserFile file, InputStream content) {
    }
}
