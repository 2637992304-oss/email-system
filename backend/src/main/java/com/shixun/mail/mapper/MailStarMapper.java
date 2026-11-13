package com.shixun.mail.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shixun.mail.entity.MailStar;
import com.shixun.mail.vo.MailListVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Set;

@Mapper
public interface MailStarMapper extends BaseMapper<MailStar> {

    /** 获取用户所有星标邮件ID集合 */
    Set<Long> selectStarredMailIds(@Param("userId") Long userId);

    /** 获取用户当前未删除的星标邮件列表 */
    List<MailListVO> selectVisibleStarredList(@Param("userId") Long userId);

    /** 检查某封邮件是否被用户星标 */
    boolean existsByUserAndMail(@Param("userId") Long userId, @Param("mailId") Long mailId);
}
