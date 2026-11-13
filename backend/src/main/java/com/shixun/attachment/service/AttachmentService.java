package com.shixun.attachment.service;

import com.shixun.attachment.entity.Attachment;
import com.shixun.attachment.mapper.AttachmentMapper;
import com.shixun.attachment.util.FileStorageUtil;
import com.shixun.common.exception.BusinessException;
import com.shixun.common.security.AuthUserContext;
import com.shixun.mail.vo.AttachmentVO;
import com.shixun.user.entity.User;
import com.shixun.user.mapper.UserMapper;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.List;

@Service
public class AttachmentService {

    private final AttachmentMapper attachmentMapper;
    private final UserMapper userMapper;
    private final FileStorageUtil fileStorageUtil;

    public AttachmentService(AttachmentMapper attachmentMapper,
                             UserMapper userMapper,
                             FileStorageUtil fileStorageUtil) {
        this.attachmentMapper = attachmentMapper;
        this.userMapper = userMapper;
        this.fileStorageUtil = fileStorageUtil;
    }

    @Transactional
    public AttachmentVO upload(Long mailId, MultipartFile file) {
        Long currentUserId = requireCurrentUserId();
        requireMailId(mailId);

        // 当前表结构要求 attachment.mail_id 非空，所以阶段三先按“已有邮件上传附件”落地。
        if (!attachmentMapper.existsUploadPermission(mailId, currentUserId)) {
            throw BusinessException.of(403, "无权给该邮件上传附件");
        }

        FileStorageUtil.StoredFile storedFile = fileStorageUtil.store(file, currentUserId, mailId);

        Attachment attachment = new Attachment();
        attachment.setMailId(mailId);
        attachment.setUploaderId(currentUserId);
        attachment.setFileName(storedFile.fileName());
        attachment.setFilePath(storedFile.filePath());
        attachment.setFileSize(storedFile.fileSize());
        attachment.setFileType(storedFile.fileType());
        try {
            if (attachmentMapper.insertAttachment(attachment) != 1) {
                throw BusinessException.of(500, "附件记录保存失败");
            }
            if (attachmentMapper.updateMailHasAttachment(mailId) != 1) {
                throw BusinessException.of(500, "邮件附件状态更新失败");
            }
        } catch (RuntimeException ex) {
            try {
                fileStorageUtil.delete(storedFile.filePath());
            } catch (RuntimeException cleanupException) {
                ex.addSuppressed(cleanupException);
            }
            throw ex;
        }

        return toVO(attachment);
    }

    public List<AttachmentVO> listByMailId(Long mailId) {
        Long currentUserId = requireCurrentUserId();
        requireMailId(mailId);
        if (!attachmentMapper.existsDownloadPermission(mailId, currentUserId)) {
            throw BusinessException.of(403, "无权查看该邮件附件");
        }
        return attachmentMapper.selectVOByMailId(mailId);
    }

    public DownloadFile download(Long attachmentId) {
        Long currentUserId = requireCurrentUserId();
        if (attachmentId == null || attachmentId <= 0) {
            throw BusinessException.of(400, "附件ID不合法");
        }

        Attachment attachment = attachmentMapper.selectById(attachmentId);
        if (attachment == null) {
            throw BusinessException.of(404, "附件不存在");
        }
        if (!attachmentMapper.existsDownloadPermission(attachment.getMailId(), currentUserId)) {
            throw BusinessException.of(403, "无权下载该附件");
        }

        Path filePath = fileStorageUtil.load(attachment.getFilePath());
        return new DownloadFile(new FileSystemResource(filePath), attachment.getFileName(), attachment.getFileType());
    }

    private AttachmentVO toVO(Attachment attachment) {
        AttachmentVO vo = new AttachmentVO();
        vo.setAttachmentId(attachment.getAttachmentId());
        vo.setFileName(attachment.getFileName());
        vo.setFileSize(attachment.getFileSize());
        vo.setFileType(attachment.getFileType());
        vo.setDownloadUrl("/api/attachment/download/" + attachment.getAttachmentId());
        return vo;
    }

    private Long requireCurrentUserId() {
        Long userId = AuthUserContext.getUserId();
        User user = userId == null ? null : userMapper.findById(userId);
        if (user == null || !Integer.valueOf(1).equals(user.getStatus())) {
            throw BusinessException.of(401, "Unauthorized");
        }
        return userId;
    }

    private void requireMailId(Long mailId) {
        if (mailId == null || mailId <= 0) {
            throw BusinessException.of(400, "邮件ID不合法");
        }
    }

    public record DownloadFile(Resource resource, String fileName, String fileType) {
    }
}
