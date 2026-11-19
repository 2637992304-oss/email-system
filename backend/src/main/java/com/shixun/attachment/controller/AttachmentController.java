package com.shixun.attachment.controller;

import com.shixun.attachment.service.AttachmentService;
import com.shixun.common.api.ApiResponse;
import com.shixun.mail.vo.AttachmentVO;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/attachment")
public class AttachmentController {

    private final AttachmentService attachmentService;

    public AttachmentController(AttachmentService attachmentService) {
        this.attachmentService = attachmentService;
    }

    @PostMapping("/upload")
    public ApiResponse<AttachmentVO> upload(@RequestParam(required=false) Long mailId,
                                            @RequestParam("file") MultipartFile file) {
        return ApiResponse.success("上传成功", attachmentService.upload(mailId, file));
    }

    @GetMapping("/mail/{mailId}")
    public ApiResponse<List<AttachmentVO>> listByMailId(@PathVariable Long mailId) {
        return ApiResponse.success(attachmentService.listByMailId(mailId));
    }

    @GetMapping("/download/{id}")
    public ResponseEntity<Resource> download(@PathVariable("id") Long attachmentId) throws IOException {
        AttachmentService.DownloadFile downloadFile = attachmentService.download(attachmentId);
        MediaType mediaType = resolveMediaType(downloadFile.fileType());
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(downloadFile.fileName(), StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(downloadFile.resource().contentLength())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(downloadFile.resource());
    }

    private MediaType resolveMediaType(String fileType) {
        if (fileType == null || fileType.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            return MediaType.parseMediaType(fileType);
        } catch (IllegalArgumentException ex) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
