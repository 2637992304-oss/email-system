package com.shixun.mail.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shixun.mail.entity.SpamRecord;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SpamRecordMapper extends BaseMapper<SpamRecord> {
}
