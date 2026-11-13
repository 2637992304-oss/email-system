package com.shixun.user.mapper;

import com.shixun.user.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserMapper {

    User findById(@Param("userId") Long userId);

    User findByEmail(@Param("email") String email);

    int insert(User user);
}
