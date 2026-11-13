package com.shixun.attachment.util;

import com.shixun.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileStorageUtilTest {

    Path tempDirectory;

    @BeforeEach
    void createWorkspaceTempDirectory() throws IOException {
        Path testRoot = Path.of("target", "attachment-tests");
        Files.createDirectories(testRoot);
        tempDirectory = Files.createTempDirectory(testRoot, "storage-");
    }

    @Test
    void storesFileInUserAndMailDirectoryWithSafeName() throws Exception {
        FileStorageUtil storage = new FileStorageUtil(tempDirectory.toString());
        byte[] content = "phase-four".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile(
                "file", "../report?.txt", "text/plain", content);

        FileStorageUtil.StoredFile stored = storage.store(file, 7L, 19L);

        assertEquals("report_.txt", stored.fileName());
        assertTrue(stored.filePath().startsWith("user-7/mail-19/"));
        assertFalse(stored.filePath().contains(".."));
        assertArrayEquals(content, Files.readAllBytes(storage.load(stored.filePath())));
    }

    @Test
    void rejectsPathTraversalAndAbsolutePaths() {
        FileStorageUtil storage = new FileStorageUtil(tempDirectory.toString());

        BusinessException traversal = assertThrows(
                BusinessException.class, () -> storage.load("../secret.txt"));
        BusinessException absolute = assertThrows(
                BusinessException.class,
                () -> storage.load(tempDirectory.resolve("secret.txt").toAbsolutePath().toString()));

        assertEquals(400, traversal.getCode());
        assertEquals(400, absolute.getCode());
    }

    @Test
    void reportsMissingPhysicalFileWithoutLeakingPath() {
        FileStorageUtil storage = new FileStorageUtil(tempDirectory.toString());

        BusinessException exception = assertThrows(
                BusinessException.class, () -> storage.load("user-1/mail-1/20260610/missing.txt"));

        assertEquals(404, exception.getCode());
        assertEquals("附件文件不存在或已被清理", exception.getMessage());
    }

    @Test
    void deletesStoredFileWhenCompensationIsRequired() {
        FileStorageUtil storage = new FileStorageUtil(tempDirectory.toString());
        MockMultipartFile file = new MockMultipartFile(
                "file", "cleanup.txt", "text/plain", "cleanup".getBytes(StandardCharsets.UTF_8));
        FileStorageUtil.StoredFile stored = storage.store(file, 1L, 2L);
        Path storedPath = storage.load(stored.filePath());

        storage.delete(stored.filePath());

        assertFalse(Files.exists(storedPath));
    }
}
