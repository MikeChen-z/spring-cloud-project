package com.study.cloud.user.controller;

import com.study.cloud.user.dto.LoginRequest;
import com.study.cloud.user.entity.User;
import com.study.cloud.user.service.UserService;
import com.study.cloud.user.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 登录接口：校验账号密码，通过后签发 JWT。
 *
 * 调用方式（经过网关）：
 *   POST http://localhost:9000/api/login
 *   {"username":"张三","password":"123456"}
 *
 * 该接口在网关白名单中，无需携带 token 即可访问。
 */
@RestController
@RequiredArgsConstructor
public class LoginController {

    private final UserService userService;
    private final JwtUtil jwtUtil;

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginRequest request) {
        // 校验失败会抛 ResponseStatusException(401)，由 Spring 转成错误响应
        User user = userService.login(request.username(), request.password());

        // 校验通过：用用户 id 和用户名签发 token
        String token = jwtUtil.generate(user.getId(), user.getName());

        return Map.of(
                "token", token,
                "userId", user.getId(),
                "username", user.getName()
        );
    }
}
