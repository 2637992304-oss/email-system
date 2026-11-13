package com.shixun.attachment.mapper;

import com.shixun.attachment.entity.Attachment;
import com.shixun.mail.vo.AttachmentVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AttachmentMapper {

    int insertAttachment(Attachment attachment);

    Attachment selectById(@Param("attachmentId") Long attachmentId);

    List<AttachmentVO> selectVOByMailId(@Param("mailId") Long mailId);

    boolean existsUploadPermission(@Param("mailId") Long mailId, @Param("userId") Long userId);

    boolean existsDownloadPermission(@Param("mailId") Long mailId, @Param("userId") Long userId);

    int updateMailHasAttachment(@Param("mailId") Long mailId);
}
