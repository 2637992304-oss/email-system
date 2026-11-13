package com.shixun.mail.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shixun.mail.entity.UserContactList;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Set;

@Mapper
public interface UserContactListMapper extends BaseMapper<UserContactList> {

    /** 获取用户在指定名单类型中的所有联系人邮箱 */
    Set<String> selectEmailsByType(@Param("userId") Long userId, @Param("listType") String listType);

    /** 检查某个邮箱是否在用户的指定名单中 */
    boolean existsByEmailAndType(@Param("userId") Long userId,
                                 @Param("contactEmail") String contactEmail,
                                 @Param("listType") String listType);

    /** 检查某个邮箱是否已在用户的任意名单中（白名单/黑名单/VIP） */
    boolean existsByEmail(@Param("userId") Long userId,
                          @Param("contactEmail") String contactEmail);
}
