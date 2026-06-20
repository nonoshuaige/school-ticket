package com.schoolticket.user.service;

import com.schoolticket.common.BusinessException;
import com.schoolticket.user.entity.User;
import com.schoolticket.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;

    public User getUserById(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(BusinessException.USER_NOT_FOUND, "用户不存在");
        }
        return user;
    }
}
