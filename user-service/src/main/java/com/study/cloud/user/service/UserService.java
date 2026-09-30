package com.study.cloud.user.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.study.cloud.user.entity.User;
import com.study.cloud.user.mapper.UserMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 用户业务层。
 *
 * 继承 ServiceImpl 自动拥有 getById / save / removeById 等通用方法，
 * 真实项目里在这里写业务校验、组合多表查询等。
 */
@Service
public class UserService extends ServiceImpl<UserMapper, User> {

    /**
     * 登录校验：按用户名查记录，再比对密码。
     *
     * 学习工程使用明文比对；真实项目中库里存的是 BCrypt 哈希，
     * 这里应使用 passwordEncoder.matches(raw, user.getPassword())。
     *
     * 校验失败抛 401，Spring MVC 会把 ResponseStatusException 转成对应状态码响应。
     */
    public User login(String username, String password) {
        User user = lambdaQuery().eq(User::getName, username).one();
        if (user == null || !user.getPassword().equals(password)) {
            // 不区分"用户不存在"和"密码错误"，避免暴露哪些用户名已注册
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }
        return user;
    }
}
