package com.sails.ai.selfserviceapi.file.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sails.ai.selfserviceapi.file.config.FileStorageProperties;
import com.sails.ai.selfserviceapi.file.entity.UserFile;
import com.sails.ai.selfserviceapi.file.exception.FileNotFoundException;
import com.sails.ai.selfserviceapi.file.exception.FileQuotaExceededException;
import com.sails.ai.selfserviceapi.file.exception.UploadTooLargeException;
import com.sails.ai.selfserviceapi.file.repository.UserFileRepository;
import com.sails.ai.selfserviceapi.file.storage.FileStorage;
import com.sails.ai.selfserviceapi.file.storage.ObjectPaths;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;

class FileServiceTest {

    private UserFileRepository userFileRepository;
    private FileStorage fileStorage;
    private FileService fileService;

    @BeforeEach
    void setUp() {
        userFileRepository = Mockito.mock(UserFileRepository.class);
        fileStorage = Mockito.mock(FileStorage.class);
        FileStorageProperties properties = new FileStorageProperties(
                "local", null, null,
                DataSize.ofMegabytes(10), 2, DataSize.ofMegabytes(20),
                List.of("application/pdf", "text/plain"));
        ContentTypeValidator contentTypeValidator = new ContentTypeValidator(properties);
        fileService = new FileService(userFileRepository, fileStorage, contentTypeValidator, properties);

        when(userFileRepository.save(any(UserFile.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void storesTheObjectBeforePersistingTheRow() {
        MockMultipartFile file = pdf("report.pdf");

        UserFile saved = fileService.upload("user-1", 4L, file);

        verify(fileStorage).store(eq(saved.getObjectName()), eq("application/pdf"), any());
        assertThat(saved.getObjectName()).isEqualTo(ObjectPaths.object("user-1", 4L, extractFileId(saved.getObjectName())));
        assertThat(saved.getOriginalFilename()).isEqualTo("report.pdf");
        assertThat(saved.getContentType()).isEqualTo("application/pdf");
        assertThat(saved.getSizeBytes()).isEqualTo(file.getSize());
    }

    @Test
    void fallsBackToAPlaceholderNameWhenNoFilenameWasSent() {
        MockMultipartFile file = new MockMultipartFile("file", null, "application/pdf", pdfBytes());

        UserFile saved = fileService.upload("user-1", 4L, file);

        assertThat(saved.getOriginalFilename()).isEqualTo("unnamed");
    }

    @Test
    void rejectsAFileOverTheSizeCeiling() {
        byte[] oversized = new byte[11 * 1024 * 1024];
        MockMultipartFile file = new MockMultipartFile("file", "big.pdf", "application/pdf", oversized);

        assertThatThrownBy(() -> fileService.upload("user-1", 4L, file))
                .isInstanceOf(UploadTooLargeException.class)
                .extracting(e -> ((UploadTooLargeException) e).getStatus())
                .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);

        verify(fileStorage, never()).store(anyString(), anyString(), any());
    }

    @Test
    void rejectsAFifthFileWhenThePocLimitIsTwo() {
        when(userFileRepository.countByUserIdAndPocIdAndDeletedAtIsNull("user-1", 4L)).thenReturn(2L);

        assertThatThrownBy(() -> fileService.upload("user-1", 4L, pdf("report.pdf")))
                .isInstanceOf(FileQuotaExceededException.class)
                .extracting(e -> ((FileQuotaExceededException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);

        verify(fileStorage, never()).store(anyString(), anyString(), any());
    }

    @Test
    void rejectsAnUploadThatWouldExceedTheUsersTotalByteQuota() {
        when(userFileRepository.sumLiveSizeBytesByUserId("user-1"))
                .thenReturn(DataSize.ofMegabytes(20).toBytes() - 5);

        assertThatThrownBy(() -> fileService.upload("user-1", 4L, pdf("report.pdf")))
                .isInstanceOf(FileQuotaExceededException.class);

        verify(fileStorage, never()).store(anyString(), anyString(), any());
    }

    @Test
    void quotaIsCheckedBeforeContentTypeSoAFullPocRejectsFastest() {
        when(userFileRepository.countByUserIdAndPocIdAndDeletedAtIsNull("user-1", 4L)).thenReturn(2L);
        MockMultipartFile badType = new MockMultipartFile("file", "a.exe", "application/x-msdownload", new byte[]{1, 2, 3});

        assertThatThrownBy(() -> fileService.upload("user-1", 4L, badType))
                .isInstanceOf(FileQuotaExceededException.class);
    }

    @Test
    void listReturnsOnlyThatUsersFilesForThatPoc() {
        fileService.list("user-1", 4L);

        verify(userFileRepository).findByUserIdAndPocIdAndDeletedAtIsNullOrderByUploadedAtDesc("user-1", 4L);
    }

    @Test
    void downloadOpensTheStoredObjectForAnOwnedFile() {
        UserFile row = ownedRow();
        when(userFileRepository.findByIdAndUserIdAndPocIdAndDeletedAtIsNull(1L, "user-1", 4L))
                .thenReturn(Optional.of(row));
        when(fileStorage.open(row.getObjectName()))
                .thenReturn(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));

        FileService.FileDownload download = fileService.download("user-1", 4L, 1L);

        assertThat(download.file()).isSameAs(row);
    }

    /** The whole point of the claim-derived scope: an id that exists but is not yours is a 404. */
    @Test
    void downloadRefusesAFileBelongingToAnotherUserOrPoc() {
        when(userFileRepository.findByIdAndUserIdAndPocIdAndDeletedAtIsNull(1L, "user-1", 4L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> fileService.download("user-1", 4L, 1L))
                .isInstanceOf(FileNotFoundException.class);
    }

    @Test
    void deleteRemovesTheObjectBeforeMarkingTheRowDeleted() {
        UserFile row = ownedRow();
        when(userFileRepository.findByIdAndUserIdAndPocIdAndDeletedAtIsNull(1L, "user-1", 4L))
                .thenReturn(Optional.of(row));

        fileService.delete("user-1", 4L, 1L);

        var inOrder = Mockito.inOrder(fileStorage, userFileRepository);
        inOrder.verify(fileStorage).delete(row.getObjectName());
        ArgumentCaptor<UserFile> captor = ArgumentCaptor.forClass(UserFile.class);
        inOrder.verify(userFileRepository).save(captor.capture());
        assertThat(captor.getValue().getDeletedAt()).isNotNull();
    }

    @Test
    void deleteOfAnUnownedFileTouchesNoStorage() {
        when(userFileRepository.findByIdAndUserIdAndPocIdAndDeletedAtIsNull(1L, "user-1", 4L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> fileService.delete("user-1", 4L, 1L))
                .isInstanceOf(FileNotFoundException.class);

        verify(fileStorage, never()).delete(anyString());
    }

    private static MockMultipartFile pdf(String filename) {
        return new MockMultipartFile("file", filename, "application/pdf", pdfBytes());
    }

    private static byte[] pdfBytes() {
        return "%PDF-1.7\nstub".getBytes(StandardCharsets.UTF_8);
    }

    private static UserFile ownedRow() {
        UserFile row = new UserFile();
        row.setId(1L);
        row.setUserId("user-1");
        row.setPocId(4L);
        row.setObjectName(ObjectPaths.object("user-1", 4L, "file-abc"));
        row.setOriginalFilename("report.pdf");
        row.setContentType("application/pdf");
        row.setSizeBytes(100L);
        return row;
    }

    private static String extractFileId(String objectName) {
        return objectName.substring(objectName.lastIndexOf('/') + 1);
    }
}
