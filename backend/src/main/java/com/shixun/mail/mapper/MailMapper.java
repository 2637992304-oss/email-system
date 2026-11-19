package com.shixun.mail.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shixun.mail.entity.Mail;
import com.shixun.mail.entity.MailReceiver;
import com.shixun.mail.vo.MailDetailVO;
import com.shixun.mail.vo.MailListVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MailMapper extends BaseMapper<Mail> {

    List<MailListVO> selectPage(@Param("userId") Long userId, @Param("folder") String folder,
        @Param("time") java.time.LocalDateTime time, @Param("id") Long id, @Param("limit") int limit);
    List<java.util.Map<String,Object>> selectPageRecipients(@Param("ids") List<Long> ids);

    List<MailListVO> selectPriorityPage(@Param("userId") Long userId,@Param("folder") String folder,@Param("score") Integer score,@Param("id") Long id,@Param("limit") int limit);

    int insertMail(Mail mail);

    int insertMailReceiver(MailReceiver receiver);

    List<MailListVO> selectInboxList(@Param("userId") Long userId);

    List<MailListVO> selectSentBaseList(@Param("userId") Long userId);

    List<MailListVO> selectTrashBaseList(@Param("userId") Long userId);

    List<String> selectReceiverNamesByMailId(@Param("mailId") Long mailId);

    List<String> selectReceiverEmailsByMailId(@Param("mailId") Long mailId);

    MailDetailVO selectMailDetail(@Param("mailId") Long mailId);

    List<String> selectToEmailsByMailId(@Param("mailId") Long mailId);

    List<String> selectCcEmailsByMailId(@Param("mailId") Long mailId);

    boolean existsMailAccess(@Param("mailId") Long mailId, @Param("userId") Long userId);

    boolean existsSender(@Param("mailId") Long mailId, @Param("userId") Long userId);

    boolean existsReceiverRecord(@Param("mailId") Long mailId, @Param("userId") Long userId);

    boolean existsDeletedSender(@Param("mailId") Long mailId, @Param("userId") Long userId);

    boolean existsDeletedSenderAny(@Param("mailId") Long mailId);

    boolean existsDeletedReceiverRecord(@Param("mailId") Long mailId, @Param("userId") Long userId);

    boolean existsActiveReceiver(@Param("mailId") Long mailId);

    int updateReadStatus(@Param("mailId") Long mailId,
                         @Param("userId") Long userId,
                         @Param("isRead") Boolean isRead);

    int updateReceiverDeleted(@Param("mailId") Long mailId, @Param("userId") Long userId);

    int updateSenderDeleted(@Param("mailId") Long mailId, @Param("senderId") Long senderId);

    int restoreReceiverDeleted(@Param("mailId") Long mailId, @Param("userId") Long userId);

    int restoreSenderDeleted(@Param("mailId") Long mailId, @Param("senderId") Long senderId);

    int deleteReceiverRecord(@Param("mailId") Long mailId, @Param("userId") Long userId);

    int updateMailStatus(@Param("mailId") Long mailId, @Param("mailStatus") Integer mailStatus);

    // 成员6: 更新垃圾邮件标记
    int updateSpamFlag(@Param("mailId") Long mailId, @Param("isSpam") Boolean isSpam);
}
