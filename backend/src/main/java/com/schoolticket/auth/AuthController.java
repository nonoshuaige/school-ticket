package com.schoolticket.auth;

import com.schoolticket.common.Result;
import com.schoolticket.dto.LoginReq;
import com.schoolticket.dto.RegisterReq;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Value("${cookie.name}")
    private String cookieName;

    @Value("${cookie.max-age}")
    private int cookieMaxAge;

    @Value("${cookie.path}")
    private String cookiePath;

    @PostMapping("/register")
    public Result<?> register(@Valid @RequestBody RegisterReq req) {
        authService.register(req);
        return Result.success("注册成功", null);
    }

    @PostMapping("/login")
    public Result<?> login(@Valid @RequestBody LoginReq req, HttpServletResponse response) {
        String token = authService.login(req);

        Cookie cookie = new Cookie(cookieName, token);
        cookie.setHttpOnly(true);
        cookie.setPath(cookiePath);
        cookie.setMaxAge(cookieMaxAge);
        response.addCookie(cookie);

        return Result.success("登录成功", token);
    }

    @PostMapping("/logout")
    public Result<?> logout(HttpServletResponse response) {
        Cookie cookie = new Cookie(cookieName, null);
        cookie.setHttpOnly(true);
        cookie.setPath(cookiePath);
        cookie.setMaxAge(0);
        response.addCookie(cookie);
        return Result.success("退出成功", null);
    }
}