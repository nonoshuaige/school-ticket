package com.schoolticket.auth;

import com.schoolticket.common.Result;
import com.schoolticket.dto.LoginReq;
import com.schoolticket.dto.RegisterReq;
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

        org.springframework.http.ResponseCookie cookie = org.springframework.http.ResponseCookie
                .from(cookieName, token)
                .httpOnly(true)
                .path(cookiePath)
                .maxAge(cookieMaxAge)
                .sameSite("Lax")
                .build();
        response.setHeader(org.springframework.http.HttpHeaders.SET_COOKIE, cookie.toString());

        return Result.success("登录成功", token);
    }

    @PostMapping("/logout")
    public Result<?> logout(HttpServletResponse response) {
        org.springframework.http.ResponseCookie cookie = org.springframework.http.ResponseCookie
                .from(cookieName, "")
                .httpOnly(true)
                .path(cookiePath)
                .maxAge(0)
                .sameSite("Lax")
                .build();
        response.setHeader(org.springframework.http.HttpHeaders.SET_COOKIE, cookie.toString());
        return Result.success("退出成功", null);
    }
}