package com.shixun.mail.mapper;

import com.shixun.mail.entity.Mail;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface MailSearchMapper {

    List<Mail> search(@Param("userId") Long userId, @Param("keyword") String keyword, @Param("time") LocalDateTime time, @Param("id") Long id, @Param("limit") int limit);

    List<Mail> filter(@Param("userId") Long userId,
                      @Param("isRead") Boolean isRead,
                      @Param("hasAttachment") Boolean hasAttachment,
                      @Param("startTime") LocalDateTime startTime,
                      @Param("endTime") LocalDateTime endTime, @Param("folder") String folder, @Param("time") LocalDateTime time, @Param("id") Long id, @Param("limit") int limit);
}
