package com.shixun.user.controller;

import com.shixun.common.api.ApiResponse;
import com.shixun.user.dto.UserInfoResponse;
import com.shixun.user.service.UserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/user")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    public ApiResponse<UserInfoResponse> me() {
        return ApiResponse.success(userService.getCurrentUser());
    }
}
