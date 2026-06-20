package com.schoolticket.user.controller;

import com.schoolticket.common.CurrentUserHolder;
import com.schoolticket.common.Result;
import com.schoolticket.user.entity.User;
import com.schoolticket.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/info")
    public Result<User> info() {
        Long userId = CurrentUserHolder.getUserId();
        User user = userService.getUserById(userId);
        user.setPassword(null); // 不返回密码
        return Result.success(user);
    }
}
