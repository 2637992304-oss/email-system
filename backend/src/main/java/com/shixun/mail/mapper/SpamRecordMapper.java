package com.shixun.mail.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shixun.mail.entity.SpamRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SpamRecordMapper extends BaseMapper<SpamRecord> {

    @Insert("""
        INSERT INTO spam_record(mail_id,sender_id,sender_info,spam_type,keywords,reason,created_at)
        SELECT mail_id,sender_id,COALESCE(#{senderInfo},''),COALESCE(#{spamType},''),
               #{hitKeyword},COALESCE(#{riskReason},''),COALESCE(#{detectTime},NOW())
        FROM mail WHERE mail_id=#{mailId}
        """)
    int insertForMail(SpamRecord record);
}
