package com.schoolticket.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schoolticket.common.BusinessException;
import com.schoolticket.dto.LoginReq;
import com.schoolticket.dto.RegisterReq;
import com.schoolticket.user.entity.User;
import com.schoolticket.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public User register(RegisterReq req) {
        // 校验手机号是否已注册
        Long count = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getPhone, req.getPhone()));
        if (count > 0) {
            throw new BusinessException("该手机号已注册");
        }

        User user = new User();
        user.setPhone(req.getPhone());
        user.setPassword(passwordEncoder.encode(req.getPassword()));
        user.setNickname(req.getNickname() != null ? req.getNickname() : "用户" + req.getPhone().substring(7));
        userMapper.insert(user);
        return user;
    }

    public String login(LoginReq req) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getPhone, req.getPhone()));
        if (user == null) {
            throw new BusinessException("手机号未注册");
        }
        if (!passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            throw new BusinessException("密码错误");
        }
        return jwtUtil.generateToken(user.getUserId());
    }
}
