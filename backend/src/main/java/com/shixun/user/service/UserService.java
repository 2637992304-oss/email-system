package com.shixun.user.service;

import com.shixun.common.exception.BusinessException;
import com.shixun.common.security.AuthUserContext;
import com.shixun.user.dto.UserInfoResponse;
import com.shixun.user.entity.User;
import com.shixun.user.mapper.UserMapper;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    private final UserMapper userMapper;

    public UserService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public UserInfoResponse getCurrentUser() {
        Long userId = AuthUserContext.getUserId();
        if (userId == null) {
            throw BusinessException.of(401, "Unauthorized");
        }

        User user = userMapper.findById(userId);
        if (user == null) {
            throw BusinessException.of(404, "User not found");
        }

        return UserInfoResponse.builder()
                .userId(user.getUserId())
                .email(user.getEmail())
                .username(user.getUsername())
                .status(user.getStatus())
                .registerTime(user.getRegisterTime())
                .build();
    }
}
