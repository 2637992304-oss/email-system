package com.shixun.attachment.service;

import com.shixun.attachment.entity.Attachment;
import com.shixun.attachment.mapper.AttachmentMapper;
import com.shixun.attachment.util.FileStorageUtil;
import com.shixun.common.exception.BusinessException;
import com.shixun.common.security.AuthUserContext;
import com.shixun.user.entity.User;
import com.shixun.user.mapper.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AttachmentServiceTest {

    @Mock
    private AttachmentMapper attachmentMapper;

    @Mock
    private UserMapper userMapper;

    @Mock
    private FileStorageUtil fileStorageUtil;

    private AttachmentService attachmentService;

    @BeforeEach
    void setUp() {
        attachmentService = new AttachmentService(attachmentMapper, userMapper, fileStorageUtil);
        AuthUserContext.set("userId", 2L);
        User activeUser = new User();
        activeUser.setUserId(2L);
        activeUser.setStatus(1);
        when(userMapper.findById(2L)).thenReturn(activeUser);
    }

    @AfterEach
    void tearDown() {
        AuthUserContext.clear();
    }

    @Test
    void stagingModeRejectsPostSendAttachmentUpload() {
        org.springframework.test.util.ReflectionTestUtils.setField(attachmentService,"stagingOnly",true);
        BusinessException ex=assertThrows(BusinessException.class,()->attachmentService.upload(12L,new MockMultipartFile("file","x.txt","text/plain",new byte[]{1})));
        assertEquals(400,ex.getCode());
        verifyNoInteractions(attachmentMapper,fileStorageUtil);
    }

    @Test
    void rejectsDownloadWhenUserHasNoMailRelationship() {
        Attachment attachment = attachment(8L, 12L);
        when(attachmentMapper.selectById(8L)).thenReturn(attachment);
        when(attachmentMapper.existsDownloadPermission(12L, 2L)).thenReturn(false);

        BusinessException exception = assertThrows(
                BusinessException.class, () -> attachmentService.download(8L));

        assertEquals(403, exception.getCode());
        verifyNoInteractions(fileStorageUtil);
    }

    @Test
    void returnsFileOnlyAfterPermissionCheck() throws IOException {
        Attachment attachment = attachment(8L, 12L);
        Path path = Path.of("attachment.txt");
        when(attachmentMapper.selectById(8L)).thenReturn(attachment);
        when(attachmentMapper.existsDownloadPermission(12L, 2L)).thenReturn(true);
        when(fileStorageUtil.load(attachment.getFilePath())).thenReturn(path);

        AttachmentService.DownloadFile result = attachmentService.download(8L);

        assertEquals("report.txt", result.fileName());
        assertEquals(path.toFile(), result.resource().getFile());
    }

    @Test
    void rejectsDisabledUserBeforeReadingAttachmentMetadata() {
        User disabledUser = new User();
        disabledUser.setUserId(2L);
        disabledUser.setStatus(0);
        when(userMapper.findById(2L)).thenReturn(disabledUser);

        BusinessException exception = assertThrows(
                BusinessException.class, () -> attachmentService.download(8L));

        assertEquals(401, exception.getCode());
        verifyNoInteractions(attachmentMapper, fileStorageUtil);
    }

    @Test
    void removesPhysicalFileWhenDatabaseInsertFails() {
        MockMultipartFile file = new MockMultipartFile("file", "report.txt", "text/plain", "data".getBytes());
        FileStorageUtil.StoredFile storedFile = new FileStorageUtil.StoredFile(
                "report.txt", "user-2/mail-12/20260610/report.txt", 4L, "text/plain");
        when(attachmentMapper.existsUploadPermission(12L, 2L)).thenReturn(true);
        when(fileStorageUtil.store(file, 2L, 12L)).thenReturn(storedFile);
        doThrow(new IllegalStateException("database unavailable"))
                .when(attachmentMapper).insertAttachment(org.mockito.ArgumentMatchers.any(Attachment.class));

        assertThrows(IllegalStateException.class, () -> attachmentService.upload(12L, file));

        verify(fileStorageUtil).delete(storedFile.filePath());
    }

    private Attachment attachment(Long attachmentId, Long mailId) {
        Attachment attachment = new Attachment();
        attachment.setAttachmentId(attachmentId);
        attachment.setMailId(mailId);
        attachment.setFileName("report.txt");
        attachment.setFilePath("user-1/mail-12/20260610/report.txt");
        attachment.setFileType("text/plain");
        return attachment;
    }
}
