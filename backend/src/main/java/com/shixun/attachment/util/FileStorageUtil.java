package com.shixun.attachment.util;

import com.shixun.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Component
public class FileStorageUtil {

    private static final DateTimeFormatter DAY_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;

    private final Path rootDirectory;

    public FileStorageUtil(@Value("${mail.attachment.root-dir:data/uploads/attachments}") String rootDir) {
        this.rootDirectory = Paths.get(rootDir).toAbsolutePath().normalize();
    }

    public StoredFile store(MultipartFile file, Long uploaderId, Long mailId) {
        if (file == null || file.isEmpty()) {
            throw BusinessException.of(400, "上传文件不能为空");
        }
        if (uploaderId == null || uploaderId <= 0 || mailId == null || mailId <= 0) {
            throw BusinessException.of(400, "附件归属信息不合法");
        }

        String originalName = sanitizeFileName(file.getOriginalFilename());
        String storedName = UUID.randomUUID() + "-" + originalName;
        String datePath = LocalDate.now().format(DAY_FORMATTER);
        Path relativePath = Paths.get("user-" + uploaderId, "mail-" + mailId, datePath, storedName);
        Path targetPath = resolveInsideRoot(relativePath);

        try {
            createSecureDirectories(relativePath.getParent());
            try (InputStream inputStream = file.getInputStream()) {
                Files.copy(inputStream, targetPath);
            }
        } catch (IOException ex) {
            throw BusinessException.of(500, "附件保存失败", ex);
        }

        return new StoredFile(
                originalName,
                toDatabasePath(relativePath),
                file.getSize(),
                file.getContentType()
        );
    }

    public Path load(String databasePath) {
        if (databasePath == null || databasePath.isBlank()) {
            throw BusinessException.of(404, "附件文件不存在或已被清理");
        }

        Path relativePath = Paths.get(databasePath);
        Path filePath = resolveInsideRoot(relativePath);
        if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
            throw BusinessException.of(404, "附件文件不存在或已被清理");
        }
        return requireRealPathInsideRoot(filePath);
    }

    public void delete(String databasePath) {
        if (databasePath == null || databasePath.isBlank()) {
            return;
        }
        Path filePath = resolveInsideRoot(Paths.get(databasePath));
        try {
            if (Files.exists(filePath)) {
                Path realPath = requireRealPathInsideRoot(filePath);
                Files.deleteIfExists(realPath);
            }
        } catch (IOException ex) {
            throw BusinessException.of(500, "附件文件清理失败", ex);
        }
    }

    private Path resolveInsideRoot(Path relativePath) {
        if (relativePath.isAbsolute()) {
            throw BusinessException.of(400, "附件路径不合法");
        }
        Path resolved = rootDirectory.resolve(relativePath).normalize();
        if (!resolved.startsWith(rootDirectory)) {
            throw BusinessException.of(400, "附件路径不合法");
        }
        return resolved;
    }

    private void createSecureDirectories(Path relativeDirectory) throws IOException {
        Files.createDirectories(rootDirectory);
        Path realRoot = rootDirectory.toRealPath();
        Path current = rootDirectory;
        for (Path segment : relativeDirectory) {
            current = current.resolve(segment);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current)
                        || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw BusinessException.of(400, "附件目录不合法");
                }
            } else {
                Files.createDirectory(current);
            }
            if (!current.toRealPath().startsWith(realRoot)) {
                throw BusinessException.of(400, "附件路径不合法");
            }
        }
    }

    private Path requireRealPathInsideRoot(Path path) {
        try {
            Files.createDirectories(rootDirectory);
            Path realRoot = rootDirectory.toRealPath();
            Path realPath = path.toRealPath();
            if (!realPath.startsWith(realRoot)) {
                throw BusinessException.of(400, "附件路径不合法");
            }
            return realPath;
        } catch (IOException ex) {
            throw BusinessException.of(404, "附件文件不存在或已被清理", ex);
        }
    }

    private String sanitizeFileName(String fileName) {
        String name = fileName == null ? "attachment" : fileName.trim();
        name = name.replace("\\", "/");
        int slashIndex = name.lastIndexOf('/');
        if (slashIndex >= 0) {
            name = name.substring(slashIndex + 1);
        }
        name = name.replaceAll("[\\p{Cntrl}]", "")
                .replaceAll("[^a-zA-Z0-9._\\-\\u4e00-\\u9fa5]", "_");
        if (name.isBlank() || ".".equals(name) || "..".equals(name)) {
            return "attachment";
        }
        return name.length() > 120 ? name.substring(name.length() - 120) : name;
    }

    private String toDatabasePath(Path relativePath) {
        return relativePath.toString().replace('\\', '/');
    }

    public record StoredFile(String fileName, String filePath, Long fileSize, String fileType) {
    }
}
