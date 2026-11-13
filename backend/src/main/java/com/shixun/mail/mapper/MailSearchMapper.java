package com.shixun.mail.mapper;

import com.shixun.mail.entity.Mail;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface MailSearchMapper {

    List<Mail> search(@Param("userId") Long userId, @Param("keyword") String keyword);

    List<Mail> filter(@Param("userId") Long userId,
                      @Param("isRead") Boolean isRead,
                      @Param("hasAttachment") Boolean hasAttachment,
                      @Param("startTime") LocalDateTime startTime,
                      @Param("endTime") LocalDateTime endTime);
}
